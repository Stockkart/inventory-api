package com.inventory.hr.utils;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.AlgorithmParameters;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECFieldFp;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Canonical payloads and signature verification for the Attendance app (design DD3).
 *
 * <p>Both payloads are a fixed-order {@code key=value} line protocol: UTF-8, lines joined with
 * {@code \n}, no trailing newline. The app ({@code attendance-protocol}, TypeScript) builds the
 * same bytes; the shared fixture {@code attendance-protocol/fixtures/canonical.json} keeps the two
 * builders byte-identical. Signatures are ECDSA P-256 / SHA-256, DER-encoded ({@code
 * ECDSA-Sig-Value}); public keys are X.509 {@code SubjectPublicKeyInfo}, Base64. The server only
 * ever verifies; it never signs.
 *
 * <p>Privacy (R9.2): the punch payload has no field for coordinates. It carries the distance from
 * the shop and the accuracy, both whole metres, and the mock flag.
 */
public final class AppSignatures {

  /** First line of a punch payload, signed by the biometric-gated punch key. */
  public static final String PUNCH_VERSION = "SKAT-PUNCH-1";

  /** First line of a request envelope, signed by the device key. */
  public static final String ENVELOPE_VERSION = "SKAT-REQ-1";

  /** Field value used when a metres field has no value. */
  public static final String NO_VALUE = "-";

  private static final String SIGNATURE_ALGORITHM = "SHA256withECDSA";
  private static final String CURVE = "secp256r1";

  private static final Pattern OBJECT_ID = Pattern.compile("[0-9a-f]{24}");
  private static final Pattern UUID_LOWER =
      Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
  private static final Pattern NO_LINE_BREAK = Pattern.compile("[^\\r\\n]*");

  private static final DateTimeFormatter PHONE_TIME =
      DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT).withZone(ZoneOffset.UTC);

  private static final HexFormat HEX = HexFormat.of();

  private static final ECParameterSpec P256 = loadP256();

  private AppSignatures() {}

  /**
   * The ten fields of a punch, in payload order.
   *
   * @param pairingId 24 lowercase hex characters
   * @param shopId 24 lowercase hex characters
   * @param employeeId 24 lowercase hex characters
   * @param punchId UUID made by the app, lowercase, with hyphens
   * @param phoneTime the phone's clock at the punch, whole seconds
   * @param offline true when the punch was saved on the phone and sent later
   * @param locationChecked true when the shop's Location check was on as the app last knew it
   * @param distanceMetres whole metres from the shop, or null when none
   * @param accuracyMetres reported accuracy in whole metres, or null when none
   * @param mock true when the phone marked the reading as a mock location
   */
  public record PunchFields(
      String pairingId,
      String shopId,
      String employeeId,
      String punchId,
      Instant phoneTime,
      boolean offline,
      boolean locationChecked,
      Integer distanceMetres,
      Integer accuracyMetres,
      boolean mock) {}

  /**
   * The fields of a request envelope, in payload order.
   *
   * @param pairingId 24 lowercase hex characters
   * @param method {@code GET} or {@code POST}
   * @param path request path without the query string
   * @param query raw query string without the leading {@code ?}; null or empty when none
   * @param timestamp epoch seconds as sent in {@code X-Request-Timestamp}
   * @param nonce UUID as sent in {@code X-Request-Nonce}, lowercase
   * @param body the exact body bytes; null or empty when the request has no body
   */
  public record EnvelopeFields(
      String pairingId,
      String method,
      String path,
      String query,
      long timestamp,
      String nonce,
      byte[] body) {}

  /** The canonical punch payload bytes that the punch key signs. */
  public static byte[] punchPayload(PunchFields f) {
    Objects.requireNonNull(f, "fields");
    requireObjectId(f.pairingId(), "pairingId");
    requireObjectId(f.shopId(), "shopId");
    requireObjectId(f.employeeId(), "employeeId");
    requireUuid(f.punchId(), "punchId");
    Objects.requireNonNull(f.phoneTime(), "phoneTime");
    if (f.phoneTime().getNano() != 0) {
      throw new IllegalArgumentException("phoneTime must be whole seconds");
    }
    String text =
        String.join(
            "\n",
            PUNCH_VERSION,
            "pairingId=" + f.pairingId(),
            "shopId=" + f.shopId(),
            "employeeId=" + f.employeeId(),
            "punchId=" + f.punchId(),
            "phoneTime=" + PHONE_TIME.format(f.phoneTime()),
            "offline=" + bit(f.offline()),
            "locationChecked=" + bit(f.locationChecked()),
            "distanceMetres=" + metres(f.distanceMetres(), "distanceMetres"),
            "accuracyMetres=" + metres(f.accuracyMetres(), "accuracyMetres"),
            "mock=" + bit(f.mock()));
    return text.getBytes(StandardCharsets.UTF_8);
  }

  /** The canonical request envelope bytes that the device key signs. */
  public static byte[] requestEnvelope(EnvelopeFields f) {
    Objects.requireNonNull(f, "fields");
    requireObjectId(f.pairingId(), "pairingId");
    if (!"GET".equals(f.method()) && !"POST".equals(f.method())) {
      throw new IllegalArgumentException("method must be GET or POST");
    }
    Objects.requireNonNull(f.path(), "path");
    if (!f.path().startsWith("/") || f.path().contains("?")) {
      throw new IllegalArgumentException("path must start with / and carry no query string");
    }
    requireSingleLine(f.path(), "path");
    String query = f.query() == null ? "" : f.query();
    requireSingleLine(query, "query");
    requireUuid(f.nonce(), "nonce");
    byte[] body = f.body() == null ? new byte[0] : f.body();
    String text =
        String.join(
            "\n",
            ENVELOPE_VERSION,
            "pairingId=" + f.pairingId(),
            "method=" + f.method(),
            "path=" + f.path(),
            "query=" + query,
            "timestamp=" + f.timestamp(),
            "nonce=" + f.nonce(),
            "bodySha256=" + sha256Hex(body));
    return text.getBytes(StandardCharsets.UTF_8);
  }

  /** Lowercase hex SHA-256 of the bytes. */
  public static String sha256Hex(byte[] bytes) {
    return HEX.formatHex(sha256().digest(Objects.requireNonNull(bytes, "bytes")));
  }

  /**
   * Parses a Base64 X.509 {@code SubjectPublicKeyInfo} and checks that it is an EC key on {@code
   * secp256r1} whose point lies on the curve.
   *
   * @throws IllegalArgumentException when the text is not a usable P-256 public key
   */
  public static ECPublicKey parseP256PublicKey(String spkiBase64) {
    byte[] der;
    try {
      der = Base64.getDecoder().decode(Objects.requireNonNull(spkiBase64, "spkiBase64").trim());
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("public key is not Base64", e);
    }
    PublicKey key;
    try {
      key = KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(der));
    } catch (GeneralSecurityException | IllegalArgumentException e) {
      throw new IllegalArgumentException("public key is not an EC SubjectPublicKeyInfo", e);
    }
    if (!(key instanceof ECPublicKey ec)) {
      throw new IllegalArgumentException("public key is not an EC key");
    }
    if (!isP256(ec.getParams())) {
      throw new IllegalArgumentException("public key is not on secp256r1");
    }
    if (!onCurve(ec.getW(), P256)) {
      throw new IllegalArgumentException("public key point is not on the curve");
    }
    return ec;
  }

  /**
   * True when {@code signatureBase64} is a DER ECDSA signature over {@code payload} by the private
   * key matching {@code spkiBase64}. False for any malformed input, a key not on P-256, or a
   * signature that does not match; never throws for bad input.
   */
  public static boolean verify(String spkiBase64, byte[] payload, String signatureBase64) {
    if (spkiBase64 == null || payload == null || signatureBase64 == null) {
      return false;
    }
    ECPublicKey key;
    byte[] signature;
    try {
      key = parseP256PublicKey(spkiBase64);
      signature = Base64.getDecoder().decode(signatureBase64.trim());
    } catch (IllegalArgumentException e) {
      return false;
    }
    try {
      Signature verifier = Signature.getInstance(SIGNATURE_ALGORITHM);
      verifier.initVerify(key);
      verifier.update(payload);
      return verifier.verify(signature);
    } catch (GeneralSecurityException | IllegalArgumentException e) {
      return false;
    }
  }

  // --- helpers ---

  private static String bit(boolean b) {
    return b ? "1" : "0";
  }

  private static String metres(Integer value, String field) {
    if (value == null) {
      return NO_VALUE;
    }
    if (value < 0) {
      throw new IllegalArgumentException(field + " must not be negative");
    }
    return Integer.toString(value);
  }

  private static void requireObjectId(String value, String field) {
    if (value == null || !OBJECT_ID.matcher(value).matches()) {
      throw new IllegalArgumentException(field + " must be 24 lowercase hex characters");
    }
  }

  private static void requireUuid(String value, String field) {
    if (value == null || !UUID_LOWER.matcher(value).matches()) {
      throw new IllegalArgumentException(field + " must be a lowercase UUID with hyphens");
    }
  }

  private static void requireSingleLine(String value, String field) {
    if (!NO_LINE_BREAK.matcher(value).matches()) {
      throw new IllegalArgumentException(field + " must not contain line breaks");
    }
  }

  private static MessageDigest sha256() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is required", e);
    }
  }

  private static ECParameterSpec loadP256() {
    try {
      AlgorithmParameters params = AlgorithmParameters.getInstance("EC");
      params.init(new ECGenParameterSpec(CURVE));
      return params.getParameterSpec(ECParameterSpec.class);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(CURVE + " is required", e);
    }
  }

  private static boolean isP256(ECParameterSpec spec) {
    if (spec == null || !(spec.getCurve().getField() instanceof ECFieldFp field)) {
      return false;
    }
    ECFieldFp p256Field = (ECFieldFp) P256.getCurve().getField();
    return field.getP().equals(p256Field.getP())
        && spec.getCurve().getA().equals(P256.getCurve().getA())
        && spec.getCurve().getB().equals(P256.getCurve().getB())
        && spec.getGenerator().equals(P256.getGenerator())
        && spec.getOrder().equals(P256.getOrder())
        && spec.getCofactor() == P256.getCofactor();
  }

  /** y² ≡ x³ + ax + b (mod p). */
  private static boolean onCurve(ECPoint w, ECParameterSpec spec) {
    if (w == null || w.equals(ECPoint.POINT_INFINITY)) {
      return false;
    }
    BigInteger p = ((ECFieldFp) spec.getCurve().getField()).getP();
    BigInteger x = w.getAffineX();
    BigInteger y = w.getAffineY();
    if (x.signum() < 0 || x.compareTo(p) >= 0 || y.signum() < 0 || y.compareTo(p) >= 0) {
      return false;
    }
    BigInteger left = y.modPow(BigInteger.TWO, p);
    BigInteger right =
        x.modPow(BigInteger.valueOf(3), p)
            .add(spec.getCurve().getA().multiply(x))
            .add(spec.getCurve().getB())
            .mod(p);
    return left.equals(right);
  }
}
