package com.inventory.hr.utils;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Builds synthetic Android key attestation chains for tests: a self-signed test root, an
 * intermediate, and a leaf for the punch key carrying a hand-built {@code KeyDescription}
 * extension. Everything is encoded with the small DER writer below, so no library is needed and the
 * fixtures can be bent (wrong curve, no biometric, software level, …) one field at a time.
 *
 * <p>Usage: {@code TestAttestationChains chains = new TestAttestationChains();} then {@code
 * chains.chain(punchKey, options)} and {@code new KeyAttestation(List.of(chains.rootKey()))}.
 */
public final class TestAttestationChains {

  /** KeyMint constants mirrored from the production schema. */
  public static final int PURPOSE_SIGN = 2;
  public static final int PURPOSE_VERIFY = 3;
  public static final int ALGORITHM_EC = 3;
  public static final int ALGORITHM_RSA = 1;
  public static final int EC_CURVE_P256 = 1;
  public static final int EC_CURVE_P384 = 2;
  public static final int AUTH_PASSWORD = 1;
  public static final int AUTH_BIOMETRIC = 2;
  public static final int LEVEL_SOFTWARE = 0;
  public static final int LEVEL_TEE = 1;
  public static final int LEVEL_STRONGBOX = 2;

  /** Knobs of the generated KeyDescription; defaults describe a good punch key. */
  public static final class Options {
    public int attestationSecurityLevel = LEVEL_TEE;
    public byte[] challenge = "claim-token".getBytes(StandardCharsets.UTF_8);
    public List<Integer> purpose = List.of(PURPOSE_SIGN);
    public Integer algorithm = ALGORITHM_EC;
    public Integer ecCurve = EC_CURVE_P256;
    public boolean noAuthRequired = false;
    public Integer userAuthType = AUTH_BIOMETRIC;
    /** null = absent (auth per use). */
    public Integer authTimeout = null;
    /** When false the leaf has no attestation extension at all. */
    public boolean withExtension = true;

    public Options level(int level) {
      this.attestationSecurityLevel = level;
      return this;
    }

    public Options challenge(byte[] challenge) {
      this.challenge = challenge;
      return this;
    }

    public Options purpose(Integer... purposes) {
      this.purpose = List.of(purposes);
      return this;
    }

    public Options algorithm(Integer algorithm) {
      this.algorithm = algorithm;
      return this;
    }

    public Options ecCurve(Integer curve) {
      this.ecCurve = curve;
      return this;
    }

    public Options noAuthRequired() {
      this.noAuthRequired = true;
      this.userAuthType = null;
      return this;
    }

    public Options userAuthType(Integer type) {
      this.userAuthType = type;
      return this;
    }

    public Options authTimeout(Integer seconds) {
      this.authTimeout = seconds;
      return this;
    }

    public Options withoutExtension() {
      this.withExtension = false;
      return this;
    }
  }

  private final KeyPair root;
  private final KeyPair intermediate;
  private final X509Certificate rootCert;
  private final X509Certificate intermediateCert;

  public TestAttestationChains() throws GeneralSecurityException {
    root = p256();
    intermediate = p256();
    rootCert = certificate("Test Attestation Root", root.getPublic(), "Test Attestation Root",
        root.getPrivate(), null);
    intermediateCert = certificate("Test Attestation Root", intermediate.getPublic(),
        "Test Attestation Intermediate", root.getPrivate(), null);
  }

  public PublicKey rootKey() {
    return root.getPublic();
  }

  /** A fresh P-256 pair standing in for the phone's punch key. */
  public static KeyPair p256() throws GeneralSecurityException {
    KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
    g.initialize(new ECGenParameterSpec("secp256r1"));
    return g.generateKeyPair();
  }

  /** Base64 DER SPKI, as the phone sends its punch key. */
  public static String spki(PublicKey key) {
    return Base64.getEncoder().encodeToString(key.getEncoded());
  }

  /** Leaf, intermediate, root — Base64 DER, as {@code KeyStore.getCertificateChain} returns. */
  public List<String> chain(PublicKey punchKey, Options options) throws GeneralSecurityException {
    return encode(List.of(leaf(punchKey, options), intermediateCert, rootCert));
  }

  /** The same chain without the root certificate (phones may omit it). */
  public List<String> chainWithoutRoot(PublicKey punchKey, Options options)
      throws GeneralSecurityException {
    return encode(List.of(leaf(punchKey, options), intermediateCert));
  }

  /** A chain whose leaf is signed by a key that is not the intermediate's. */
  public List<String> brokenChain(PublicKey punchKey, Options options)
      throws GeneralSecurityException {
    KeyPair stranger = p256();
    X509Certificate leaf = certificate("Test Attestation Intermediate", punchKey,
        "Android Keystore Key", stranger.getPrivate(), keyDescription(options));
    return encode(List.of(leaf, intermediateCert, rootCert));
  }

  /** A well-formed chain to a different, untrusted root. */
  public static List<String> chainToOtherRoot(PublicKey punchKey, Options options)
      throws GeneralSecurityException {
    return new TestAttestationChains().chain(punchKey, options);
  }

  private X509Certificate leaf(PublicKey punchKey, Options options)
      throws GeneralSecurityException {
    return certificate("Test Attestation Intermediate", punchKey, "Android Keystore Key",
        intermediate.getPrivate(), options.withExtension ? keyDescription(options) : null);
  }

  private static List<String> encode(List<X509Certificate> certs) throws GeneralSecurityException {
    List<String> out = new ArrayList<>();
    for (X509Certificate c : certs) {
      out.add(Base64.getEncoder().encodeToString(c.getEncoded()));
    }
    return out;
  }

  // ---- KeyDescription ------------------------------------------------------------------------

  /** DER {@code KeyDescription} (the extension value; the OCTET STRING wrapper is added by X.509). */
  public static byte[] keyDescription(Options o) {
    byte[] hardware = authorizationList(o);
    return Der.sequence(
        Der.integer(300),                                 // attestationVersion
        Der.enumerated(o.attestationSecurityLevel),       // attestationSecurityLevel
        Der.integer(300),                                 // keyMintVersion
        Der.enumerated(o.attestationSecurityLevel),       // keyMintSecurityLevel
        Der.octetString(o.challenge),                     // attestationChallenge
        Der.octetString(new byte[0]),                     // uniqueId
        Der.sequence(                                     // softwareEnforced
            Der.explicit(709, Der.octetString("app-id".getBytes(StandardCharsets.UTF_8)))),
        hardware);                                        // hardwareEnforced
  }

  private static byte[] authorizationList(Options o) {
    List<byte[]> members = new ArrayList<>();
    if (o.purpose != null) {
      byte[][] purposes = o.purpose.stream().map(Der::integer).toArray(byte[][]::new);
      members.add(Der.explicit(1, Der.set(purposes)));
    }
    if (o.algorithm != null) {
      members.add(Der.explicit(2, Der.integer(o.algorithm)));
    }
    members.add(Der.explicit(3, Der.integer(256)));                 // keySize
    members.add(Der.explicit(5, Der.set(Der.integer(4))));          // digest SHA-256
    if (o.ecCurve != null) {
      members.add(Der.explicit(10, Der.integer(o.ecCurve)));
    }
    if (o.noAuthRequired) {
      members.add(Der.explicit(503, Der.nul()));
    }
    if (o.userAuthType != null) {
      members.add(Der.explicit(504, Der.integer(o.userAuthType)));
    }
    if (o.authTimeout != null) {
      members.add(Der.explicit(505, Der.integer(o.authTimeout)));
    }
    members.add(Der.explicit(702, Der.integer(0)));                 // origin GENERATED
    return Der.sequence(members.toArray(byte[][]::new));
  }

  // ---- X.509 ---------------------------------------------------------------------------------

  private static final String OID_ECDSA_SHA256 = "1.2.840.10045.4.3.2";
  private static final String OID_COMMON_NAME = "2.5.4.3";

  /**
   * A v3 certificate signed with {@code SHA256withECDSA}. {@code extension} is the DER
   * KeyDescription to put under {@link KeyAttestation#EXTENSION_OID}; null for none.
   */
  private static X509Certificate certificate(String issuer, PublicKey subjectKey, String subject,
      PrivateKey signer, byte[] extension) throws GeneralSecurityException {
    byte[] algorithm = Der.sequence(Der.oid(OID_ECDSA_SHA256));
    List<byte[]> tbsFields = new ArrayList<>(List.of(
        Der.explicit(0, Der.integer(2)),                              // version v3
        Der.integer(BigInteger.valueOf(System.nanoTime()).abs().add(BigInteger.ONE)),
        algorithm,
        name(issuer),
        Der.sequence(Der.utcTime("240101000000Z"), Der.utcTime("480101000000Z")),
        name(subject),
        subjectKey.getEncoded()));
    if (extension != null) {
      byte[] ext = Der.sequence(Der.oid(KeyAttestation.EXTENSION_OID), Der.octetString(extension));
      tbsFields.add(Der.explicit(3, Der.sequence(ext)));
    }
    byte[] tbs = Der.sequence(tbsFields.toArray(byte[][]::new));
    Signature s = Signature.getInstance("SHA256withECDSA");
    s.initSign(signer);
    s.update(tbs);
    byte[] cert = Der.sequence(tbs, algorithm, Der.bitString(s.sign()));
    return (X509Certificate) CertificateFactory.getInstance("X.509")
        .generateCertificate(new ByteArrayInputStream(cert));
  }

  private static byte[] name(String commonName) {
    return Der.sequence(Der.set(Der.sequence(Der.oid(OID_COMMON_NAME),
        Der.utf8String(commonName))));
  }

  // ---- DER writer ----------------------------------------------------------------------------

  /** Minimal DER encoder for the fixtures above. */
  public static final class Der {
    private Der() {}

    public static byte[] sequence(byte[]... children) {
      return tlv(0x30, concat(children));
    }

    public static byte[] set(byte[]... children) {
      return tlv(0x31, concat(children));
    }

    public static byte[] integer(long value) {
      return integer(BigInteger.valueOf(value));
    }

    public static byte[] integer(BigInteger value) {
      return tlv(0x02, value.toByteArray());
    }

    public static byte[] enumerated(int value) {
      return tlv(0x0A, BigInteger.valueOf(value).toByteArray());
    }

    public static byte[] octetString(byte[] bytes) {
      return tlv(0x04, bytes);
    }

    public static byte[] nul() {
      return tlv(0x05, new byte[0]);
    }

    public static byte[] bitString(byte[] bytes) {
      byte[] content = new byte[bytes.length + 1];
      System.arraycopy(bytes, 0, content, 1, bytes.length);
      return tlv(0x03, content);
    }

    public static byte[] utf8String(String s) {
      return tlv(0x0C, s.getBytes(StandardCharsets.UTF_8));
    }

    public static byte[] utcTime(String yymmddhhmmssZ) {
      return tlv(0x17, yymmddhhmmssZ.getBytes(StandardCharsets.US_ASCII));
    }

    /** {@code [tag] EXPLICIT inner}: context-specific, constructed, high-tag form when ≥ 31. */
    public static byte[] explicit(int tag, byte[] inner) {
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      if (tag < 31) {
        out.write(0xA0 | tag);
      } else {
        out.write(0xBF);
        writeBase128(out, tag);
      }
      writeLength(out, inner.length);
      out.writeBytes(inner);
      return out.toByteArray();
    }

    public static byte[] oid(String dotted) {
      String[] parts = dotted.split("\\.");
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      out.write(Integer.parseInt(parts[0]) * 40 + Integer.parseInt(parts[1]));
      for (int i = 2; i < parts.length; i++) {
        writeBase128(out, Long.parseLong(parts[i]));
      }
      return tlv(0x06, out.toByteArray());
    }

    private static byte[] tlv(int tag, byte[] content) {
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      out.write(tag);
      writeLength(out, content.length);
      out.writeBytes(content);
      return out.toByteArray();
    }

    private static void writeLength(ByteArrayOutputStream out, int length) {
      if (length < 0x80) {
        out.write(length);
        return;
      }
      byte[] bytes = BigInteger.valueOf(length).toByteArray();
      int start = bytes[0] == 0 ? 1 : 0;
      out.write(0x80 | (bytes.length - start));
      out.write(bytes, start, bytes.length - start);
    }

    private static void writeBase128(ByteArrayOutputStream out, long value) {
      byte[] tmp = new byte[10];
      int n = 0;
      do {
        tmp[n++] = (byte) (value & 0x7F);
        value >>>= 7;
      } while (value != 0);
      for (int i = n - 1; i >= 0; i--) {
        out.write(tmp[i] | (i > 0 ? 0x80 : 0));
      }
    }

    private static byte[] concat(byte[]... parts) {
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      for (byte[] p : parts) {
        out.writeBytes(p);
      }
      return out.toByteArray();
    }
  }
}
