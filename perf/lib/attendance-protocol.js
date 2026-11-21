/**
 * Attendance app wire protocol, pure JavaScript, for load tools that cannot import the
 * TypeScript package `inventory-platform/platform/attendance-protocol`.
 *
 * Runs unchanged under k6 (>= 1.0, where WebCrypto is the global `crypto`) and Node 20 (`.mjs`
 * callers, or `"type": "module"` in `perf/package.json`). No imports, no `TextEncoder`, no
 * `Buffer`: k6 has neither, so UTF-8, Base64 and hex are written out here.
 *
 * The byte layouts mirror `core/hr/src/main/java/com/inventory/hr/utils/AppSignatures.java`:
 *
 *   - `canonicalPunchPayload`    → `SKAT-PUNCH-1`, ten `key=value` lines, `\n`-joined, no trailing
 *                                   newline; signed by the biometric punch key.
 *   - `canonicalRequestEnvelope` → `SKAT-REQ-1`, seven lines ending in `bodySha256`; signed by the
 *                                   device key and sent in `X-Request-Signature`.
 *
 * `perf/check-protocol.mjs` asserts both builders against the shared fixture
 * `core/hr/src/test/resources/attendance-protocol/fixtures/canonical.json`, byte for byte.
 *
 * Signatures: the server verifies `SHA256withECDSA` with a DER `ECDSA-Sig-Value` (SEQUENCE of two
 * INTEGERs). WebCrypto (k6 and Node alike) returns the raw IEEE P1363 `r || s` (64 bytes for
 * P-256), so `rawToDerEcdsa` converts; `derToRawEcdsa` is the inverse for self-checks.
 */

export const PUNCH_VERSION = 'SKAT-PUNCH-1';
export const ENVELOPE_VERSION = 'SKAT-REQ-1';
export const NO_VALUE = '-';

export const ECDSA_P256 = { name: 'ECDSA', namedCurve: 'P-256' };
export const ECDSA_SHA256 = { name: 'ECDSA', hash: 'SHA-256' };

const OBJECT_ID = /^[0-9a-f]{24}$/;
const UUID_LOWER = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;

// ---- bytes ------------------------------------------------------------------------------------

/** UTF-8 encodes a string (handles surrogate pairs); replaces `TextEncoder`, absent in k6. */
export function utf8Bytes(text) {
  const out = [];
  for (let i = 0; i < text.length; i++) {
    let cp = text.charCodeAt(i);
    if (cp >= 0xd800 && cp <= 0xdbff && i + 1 < text.length) {
      const low = text.charCodeAt(i + 1);
      if (low >= 0xdc00 && low <= 0xdfff) {
        cp = 0x10000 + ((cp - 0xd800) << 10) + (low - 0xdc00);
        i++;
      }
    }
    if (cp < 0x80) {
      out.push(cp);
    } else if (cp < 0x800) {
      out.push(0xc0 | (cp >> 6), 0x80 | (cp & 0x3f));
    } else if (cp < 0x10000) {
      out.push(0xe0 | (cp >> 12), 0x80 | ((cp >> 6) & 0x3f), 0x80 | (cp & 0x3f));
    } else {
      out.push(
        0xf0 | (cp >> 18),
        0x80 | ((cp >> 12) & 0x3f),
        0x80 | ((cp >> 6) & 0x3f),
        0x80 | (cp & 0x3f),
      );
    }
  }
  return new Uint8Array(out);
}

export function bytesToHex(bytes) {
  let s = '';
  for (let i = 0; i < bytes.length; i++) {
    s += (bytes[i] < 16 ? '0' : '') + bytes[i].toString(16);
  }
  return s;
}

export function hexToBytes(hex) {
  if (hex.length % 2 !== 0) {
    throw new Error('hex must have an even length');
  }
  const out = new Uint8Array(hex.length / 2);
  for (let i = 0; i < out.length; i++) {
    out[i] = parseInt(hex.substr(i * 2, 2), 16);
  }
  return out;
}

const B64 = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/';

/** Standard Base64 with padding (what `java.util.Base64.getDecoder()` reads). */
export function base64Encode(bytes) {
  let s = '';
  let i = 0;
  for (; i + 2 < bytes.length; i += 3) {
    const n = (bytes[i] << 16) | (bytes[i + 1] << 8) | bytes[i + 2];
    s += B64[(n >> 18) & 63] + B64[(n >> 12) & 63] + B64[(n >> 6) & 63] + B64[n & 63];
  }
  if (i < bytes.length) {
    const n = (bytes[i] << 16) | (i + 1 < bytes.length ? bytes[i + 1] << 8 : 0);
    s += B64[(n >> 18) & 63] + B64[(n >> 12) & 63];
    s += i + 1 < bytes.length ? B64[(n >> 6) & 63] : '=';
    s += '=';
  }
  return s;
}

export function base64Decode(text) {
  const clean = text.replace(/[^A-Za-z0-9+/]/g, '');
  const out = [];
  let buffer = 0;
  let bits = 0;
  for (let i = 0; i < clean.length; i++) {
    buffer = (buffer << 6) | B64.indexOf(clean[i]);
    bits += 6;
    if (bits >= 8) {
      bits -= 8;
      out.push((buffer >> bits) & 0xff);
    }
  }
  return new Uint8Array(out);
}

/** A standalone ArrayBuffer holding exactly these bytes (WebCrypto in k6 wants ArrayBuffer input). */
export function toArrayBuffer(bytes) {
  if (bytes.byteOffset === 0 && bytes.byteLength === bytes.buffer.byteLength) {
    return bytes.buffer;
  }
  return bytes.slice().buffer;
}

export function concatBytes(a, b) {
  const out = new Uint8Array(a.length + b.length);
  out.set(a, 0);
  out.set(b, a.length);
  return out;
}

// ---- hashing ----------------------------------------------------------------------------------

function subtle() {
  const s = globalThis.crypto && globalThis.crypto.subtle;
  if (!s) {
    throw new Error('WebCrypto (globalThis.crypto.subtle) is required: k6 >= 1.0 or Node >= 20');
  }
  return s;
}

/** Lowercase hex SHA-256, the same text `AppSignatures.sha256Hex` produces. */
export async function sha256Hex(bytes) {
  const digest = await subtle().digest('SHA-256', toArrayBuffer(bytes));
  return bytesToHex(new Uint8Array(digest));
}

// ---- canonical payloads -----------------------------------------------------------------------

function requireObjectId(value, field) {
  if (typeof value !== 'string' || !OBJECT_ID.test(value)) {
    throw new Error(`${field} must be 24 lowercase hex characters`);
  }
}

function requireUuid(value, field) {
  if (typeof value !== 'string' || !UUID_LOWER.test(value)) {
    throw new Error(`${field} must be a lowercase UUID with hyphens`);
  }
}

function bit(b) {
  return b ? '1' : '0';
}

function metres(value, field) {
  if (value === null || value === undefined) {
    return NO_VALUE;
  }
  if (!Number.isInteger(value) || value < 0) {
    throw new Error(`${field} must be a non-negative whole number of metres`);
  }
  return String(value);
}

function pad2(n) {
  return n < 10 ? `0${n}` : String(n);
}

/** `uuuu-MM-dd'T'HH:mm:ss'Z'` for whole epoch seconds, as `AppSignatures.PHONE_TIME` formats. */
export function formatPhoneTime(epochSeconds) {
  if (!Number.isInteger(epochSeconds)) {
    throw new Error('phoneTime must be whole seconds');
  }
  const d = new Date(epochSeconds * 1000);
  return (
    `${d.getUTCFullYear()}-${pad2(d.getUTCMonth() + 1)}-${pad2(d.getUTCDate())}` +
    `T${pad2(d.getUTCHours())}:${pad2(d.getUTCMinutes())}:${pad2(d.getUTCSeconds())}Z`
  );
}

/** Parses an ISO instant with whole seconds (`2025-04-03T03:42:07Z`) to epoch seconds. */
export function parsePhoneTime(iso) {
  const ms = Date.parse(iso);
  if (!Number.isFinite(ms) || ms % 1000 !== 0) {
    throw new Error(`phoneTime must be an ISO instant in whole seconds, got ${iso}`);
  }
  return ms / 1000;
}

/**
 * The bytes the punch key signs. `fields`: `pairingId`, `shopId`, `employeeId`, `punchId`,
 * `phoneTime` (epoch seconds, whole), `offline`, `locationChecked`, `distanceMetres`
 * (int or null), `accuracyMetres` (int or null), `mock`.
 */
export function canonicalPunchPayload(f) {
  requireObjectId(f.pairingId, 'pairingId');
  requireObjectId(f.shopId, 'shopId');
  requireObjectId(f.employeeId, 'employeeId');
  requireUuid(f.punchId, 'punchId');
  const text = [
    PUNCH_VERSION,
    `pairingId=${f.pairingId}`,
    `shopId=${f.shopId}`,
    `employeeId=${f.employeeId}`,
    `punchId=${f.punchId}`,
    `phoneTime=${formatPhoneTime(f.phoneTime)}`,
    `offline=${bit(f.offline)}`,
    `locationChecked=${bit(f.locationChecked)}`,
    `distanceMetres=${metres(f.distanceMetres, 'distanceMetres')}`,
    `accuracyMetres=${metres(f.accuracyMetres, 'accuracyMetres')}`,
    `mock=${bit(f.mock)}`,
  ].join('\n');
  return utf8Bytes(text);
}

/**
 * The bytes the device key signs. `fields`: `pairingId`, `method` (`GET`|`POST`), `path` (the
 * request URI the server sees, no query string), `query` (raw, without `?`, may be empty),
 * `timestamp` (epoch seconds as sent in `X-Request-Timestamp`), `nonce` (lowercase UUID), `body`
 * (`Uint8Array` of the exact body bytes, or empty / undefined when none).
 */
export async function canonicalRequestEnvelope(f) {
  requireObjectId(f.pairingId, 'pairingId');
  if (f.method !== 'GET' && f.method !== 'POST') {
    throw new Error('method must be GET or POST');
  }
  if (typeof f.path !== 'string' || !f.path.startsWith('/') || f.path.includes('?')) {
    throw new Error('path must start with / and carry no query string');
  }
  const query = f.query == null ? '' : f.query;
  if (/[\r\n]/.test(f.path) || /[\r\n]/.test(query)) {
    throw new Error('path and query must not contain line breaks');
  }
  if (!Number.isInteger(f.timestamp)) {
    throw new Error('timestamp must be whole epoch seconds');
  }
  requireUuid(f.nonce, 'nonce');
  const body = f.body == null ? new Uint8Array(0) : f.body;
  const text = [
    ENVELOPE_VERSION,
    `pairingId=${f.pairingId}`,
    `method=${f.method}`,
    `path=${f.path}`,
    `query=${query}`,
    `timestamp=${f.timestamp}`,
    `nonce=${f.nonce}`,
    `bodySha256=${await sha256Hex(body)}`,
  ].join('\n');
  return utf8Bytes(text);
}

// ---- ECDSA signature encoding -----------------------------------------------------------------

/** Minimal two's-complement DER INTEGER content for an unsigned big-endian number. */
function derUnsignedInteger(bytes) {
  let start = 0;
  while (start < bytes.length - 1 && bytes[start] === 0) {
    start++;
  }
  const trimmed = bytes.subarray(start);
  // A leading 1 bit would read as negative: prefix 0x00.
  const needsPad = trimmed[0] & 0x80;
  const content = new Uint8Array(trimmed.length + (needsPad ? 1 : 0));
  content.set(trimmed, needsPad ? 1 : 0);
  return concatBytes(new Uint8Array([0x02, content.length]), content);
}

/**
 * IEEE P1363 `r || s` (what WebCrypto's `sign` returns; 64 bytes for P-256) → DER
 * `ECDSA-Sig-Value ::= SEQUENCE { r INTEGER, s INTEGER }` (what `java.security.Signature`
 * "SHA256withECDSA" verifies). Lengths stay below 128, so short-form length octets suffice.
 */
export function rawToDerEcdsa(raw) {
  if (raw.length === 0 || raw.length % 2 !== 0) {
    throw new Error(`raw ECDSA signature must be r||s of even length, got ${raw.length}`);
  }
  const half = raw.length / 2;
  const r = derUnsignedInteger(raw.subarray(0, half));
  const s = derUnsignedInteger(raw.subarray(half));
  const body = concatBytes(r, s);
  if (body.length >= 128) {
    throw new Error('ECDSA-Sig-Value longer than expected for P-256');
  }
  return concatBytes(new Uint8Array([0x30, body.length]), body);
}

/** Inverse of {@link rawToDerEcdsa}; `size` is the per-component width (32 for P-256). */
export function derToRawEcdsa(der, size = 32) {
  if (der[0] !== 0x30 || der[1] !== der.length - 2) {
    throw new Error('not a short-form DER SEQUENCE');
  }
  let offset = 2;
  const out = new Uint8Array(size * 2);
  for (let k = 0; k < 2; k++) {
    if (der[offset] !== 0x02) {
      throw new Error('expected a DER INTEGER');
    }
    const length = der[offset + 1];
    let content = der.subarray(offset + 2, offset + 2 + length);
    offset += 2 + length;
    while (content.length > size && content[0] === 0) {
      content = content.subarray(1);
    }
    if (content.length > size) {
      throw new Error('INTEGER wider than the curve order');
    }
    out.set(content, k * size + (size - content.length));
  }
  if (offset !== der.length) {
    throw new Error('trailing bytes after ECDSA-Sig-Value');
  }
  return out;
}

/** Signs `bytes` with a WebCrypto P-256 private `CryptoKey` and returns the Base64 DER signature. */
export async function signDerBase64(privateKey, bytes) {
  const raw = await subtle().sign(ECDSA_SHA256, privateKey, toArrayBuffer(bytes));
  return base64Encode(rawToDerEcdsa(new Uint8Array(raw)));
}

/** Imports a Base64 PKCS#8 P-256 private key for signing. */
export function importPrivateKey(pkcs8Base64) {
  return subtle().importKey('pkcs8', toArrayBuffer(base64Decode(pkcs8Base64)), ECDSA_P256, false, [
    'sign',
  ]);
}

/**
 * A fresh extractable P-256 key pair as Base64 SPKI (public, what the server stores) and
 * Base64 PKCS#8 (private, what the fixture holds; test-only).
 */
export async function generateKeyPairBase64() {
  const s = subtle();
  const pair = await s.generateKey(ECDSA_P256, true, ['sign', 'verify']);
  const spki = new Uint8Array(await s.exportKey('spki', pair.publicKey));
  const pkcs8 = new Uint8Array(await s.exportKey('pkcs8', pair.privateKey));
  return { publicKeySpki: base64Encode(spki), privateKeyPkcs8: base64Encode(pkcs8), pair };
}

// ---- URLs -------------------------------------------------------------------------------------

/**
 * Splits `http(s)://host[:port][/base]` into `origin` and `basePath` (no trailing slash), like the
 * app's `parseHttpsBase`. The app refuses plain http; load tools may hit a local http container,
 * so http is allowed here and the caller decides whether to warn.
 */
export function parseBaseUrl(baseUrl) {
  const match = /^(https?):\/\/([^\s/?#]+)(\/[^\s?#]*)?$/.exec((baseUrl || '').trim());
  if (!match) {
    throw new Error(`BASE_URL must look like https://host[:port]/api/v1, got ${JSON.stringify(baseUrl)}`);
  }
  return {
    scheme: match[1],
    origin: `${match[1]}://${match[2]}`,
    basePath: (match[3] || '').replace(/\/+$/, ''),
  };
}

/** Routes under the `/api/v1` base, same list as the app's `ROUTES`. */
export const ROUTES = {
  claim: '/hr/attendance-app/pairings/claim',
  complete: '/hr/attendance-app/pairings/complete',
  abandon: '/hr/attendance-app/pairings/abandon',
  latestConsent: '/hr/attendance-app/consent/latest',
  me: '/hr/attendance-app/me',
  punches: '/hr/attendance-app/punches',
  consent: '/hr/attendance-app/consent',
  unpairMe: '/hr/attendance-app/pairings/me/unpair',
};

/** Header names, identical to `AttendanceAppAuthInterceptor`. */
export const HEADERS = {
  pairingId: 'X-Pairing-Id',
  timestamp: 'X-Request-Timestamp',
  nonce: 'X-Request-Nonce',
  signature: 'X-Request-Signature',
  serverTime: 'X-Server-Time',
};
