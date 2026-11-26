package com.inventory.hr.utils;

import com.inventory.hr.utils.constants.HrMetricsConstants.AppPairingAttestation;
import java.io.ByteArrayInputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.PublicKey;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Verifies an Android key attestation certificate chain for the punch key of a pairing (task 16.4,
 * design Security assumption 2; R5.1, R5.7).
 *
 * <p>What is proved when {@link Result#ok()} is true: the chain is well formed and every
 * certificate is signed by the next; it ends in one of the Google Hardware Attestation Root keys;
 * the first key-attestation extension counted from the root says the key lives in a {@code
 * TrustedEnvironment} or {@code StrongBox}; the challenge the hardware signed is the one the server
 * issued (the claim token); the hardware-enforced authorisation list says the key is a P-256 EC key
 * that can only sign and only after a strong biometric check (per use, or within 30 s); and the
 * attested public key is the pairing's {@code punchPublicKey}. Together these show that the punch
 * key is in secure hardware behind a biometric, not a key made by a modified app.
 *
 * <p>What is not checked in this version: certificate validity dates (attestation leaves commonly
 * carry 1970–2048, and intermediates outlive the phones they were provisioned to), the Google
 * revocation status list, and the provisioning-information extension. The verdict is recorded on
 * the pairing and counted; it is not yet used to refuse a pairing ("enforcement is a later
 * switch").
 *
 * <p>No library: the key-description extension is read with a small DER walker below (no
 * BouncyCastle on the classpath; the schema is a flat sequence of tagged optional values, see
 * {@code source.android.com/docs/security/features/keystore/attestation#schema}). Certificates
 * and signatures are handled by {@code java.security}.
 */
public final class KeyAttestation {

  /** OID of the Android key attestation extension. */
  public static final String EXTENSION_OID = "1.3.6.1.4.1.11129.2.1.17";

  /** Longest chain accepted (Android chains are 3–5 certificates). */
  static final int MAX_CHAIN_LENGTH = 8;

  /** Largest single Base64 certificate accepted. */
  static final int MAX_CERT_BASE64_CHARS = 16 * 1024;

  /** Longest {@code authTimeout} (seconds) that still counts as "a fresh biometric per punch". */
  static final long MAX_AUTH_TIMEOUT_SECONDS = 30;

  /** Security levels as the extension names them ({@code SecurityLevel} ENUMERATED). */
  public static final String LEVEL_SOFTWARE = "Software";
  public static final String LEVEL_TRUSTED_ENVIRONMENT = "TrustedEnvironment";
  public static final String LEVEL_STRONGBOX = "StrongBox";

  /**
   * {@link Result#reason()} values; the same strings as the {@code reason} tag of the attestation
   * counter ({@link AppPairingAttestation}).
   */
  public static final class Reason {
    private Reason() {}

    public static final String OK = AppPairingAttestation.OK;
    /** No certificates were sent. */
    public static final String CHAIN_EMPTY = AppPairingAttestation.CHAIN_EMPTY;
    /** Too many, too large, not Base64, or not X.509. */
    public static final String CHAIN_UNPARSEABLE = AppPairingAttestation.CHAIN_UNPARSEABLE;
    /** A certificate is not signed by the next one. */
    public static final String CHAIN_BROKEN = AppPairingAttestation.CHAIN_BROKEN;
    /** The chain does not end in a trusted root. */
    public static final String UNTRUSTED_ROOT = AppPairingAttestation.UNTRUSTED_ROOT;
    /** No certificate carries the key attestation extension. */
    public static final String NO_EXTENSION = AppPairingAttestation.NO_EXTENSION;
    /** The extension is not a KeyDescription the walker understands. */
    public static final String EXTENSION_MALFORMED = AppPairingAttestation.EXTENSION_MALFORMED;
    /** {@code attestationSecurityLevel} is Software (or unknown). */
    public static final String SOFTWARE_LEVEL = AppPairingAttestation.SOFTWARE_LEVEL;
    /** The signed challenge is not the claim token. */
    public static final String CHALLENGE_MISMATCH = AppPairingAttestation.CHALLENGE_MISMATCH;
    /** Hardware-enforced {@code purpose} lacks SIGN. */
    public static final String NOT_SIGN = AppPairingAttestation.NOT_SIGN;
    /** Hardware-enforced {@code algorithm} is not EC. */
    public static final String NOT_EC = AppPairingAttestation.NOT_EC;
    /** Hardware-enforced {@code ecCurve} is not P-256. */
    public static final String NOT_P256 = "not_p256";
    /** No strong biometric in the hardware-enforced {@code userAuthType}, or no auth required. */
    public static final String NO_BIOMETRIC = AppPairingAttestation.NO_BIOMETRIC;
    /** Hardware-enforced {@code authTimeout} is longer than 30 s. */
    public static final String AUTH_TIMEOUT = AppPairingAttestation.AUTH_TIMEOUT;
    /** The attested key is not the pairing's punch key. */
    public static final String KEY_MISMATCH = AppPairingAttestation.KEY_MISMATCH;
    /** An unexpected exception while verifying; never surfaces to the phone. */
    public static final String ERROR = AppPairingAttestation.ERROR;
  }

  /**
   * The verdict.
   *
   * @param ok every check passed
   * @param securityLevel {@link #LEVEL_TRUSTED_ENVIRONMENT}, {@link #LEVEL_STRONGBOX}, {@link
   *     #LEVEL_SOFTWARE}, or null when the extension could not be read
   * @param reason {@link Reason#OK} or the first failed check
   */
  public record Result(boolean ok, String securityLevel, String reason) {
    static Result fail(String reason) {
      return new Result(false, null, reason);
    }

    static Result fail(String reason, String level) {
      return new Result(false, level, reason);
    }
  }

  // ---- trusted roots -------------------------------------------------------------------------

  /**
   * Google Hardware Attestation Root, RSA-4096, serial {@code f1:c1:72:a6:99:ea:f5:1d}, issued
   * 2022-03-20 (the 2016 issuance, serial {@code e8:fa:19:63:14:d2:fa:18}, carries the same key, so
   * both chains verify). Copied from
   * https://developer.android.com/privacy-and-security/security-key-attestation#root_certificate
   * ("Root Certificates"); the live list is https://android.googleapis.com/attestation/root.
   */
  static final String GOOGLE_ROOT_RSA_PEM = """
      -----BEGIN CERTIFICATE-----
      MIIFHDCCAwSgAwIBAgIJAPHBcqaZ6vUdMA0GCSqGSIb3DQEBCwUAMBsxGTAXBgNV
      BAUTEGY5MjAwOWU4NTNiNmIwNDUwHhcNMjIwMzIwMTgwNzQ4WhcNNDIwMzE1MTgw
      NzQ4WjAbMRkwFwYDVQQFExBmOTIwMDllODUzYjZiMDQ1MIICIjANBgkqhkiG9w0B
      AQEFAAOCAg8AMIICCgKCAgEAr7bHgiuxpwHsK7Qui8xUFmOr75gvMsd/dTEDDJdS
      Sxtf6An7xyqpRR90PL2abxM1dEqlXnf2tqw1Ne4Xwl5jlRfdnJLmN0pTy/4lj4/7
      tv0Sk3iiKkypnEUtR6WfMgH0QZfKHM1+di+y9TFRtv6y//0rb+T+W8a9nsNL/ggj
      nar86461qO0rOs2cXjp3kOG1FEJ5MVmFmBGtnrKpa73XpXyTqRxB/M0n1n/W9nGq
      C4FSYa04T6N5RIZGBN2z2MT5IKGbFlbC8UrW0DxW7AYImQQcHtGl/m00QLVWutHQ
      oVJYnFPlXTcHYvASLu+RhhsbDmxMgJJ0mcDpvsC4PjvB+TxywElgS70vE0XmLD+O
      JtvsBslHZvPBKCOdT0MS+tgSOIfga+z1Z1g7+DVagf7quvmag8jfPioyKvxnK/Eg
      sTUVi2ghzq8wm27ud/mIM7AY2qEORR8Go3TVB4HzWQgpZrt3i5MIlCaY504LzSRi
      igHCzAPlHws+W0rB5N+er5/2pJKnfBSDiCiFAVtCLOZ7gLiMm0jhO2B6tUXHI/+M
      RPjy02i59lINMRRev56GKtcd9qO/0kUJWdZTdA2XoS82ixPvZtXQpUpuL12ab+9E
      aDK8Z4RHJYYfCT3Q5vNAXaiWQ+8PTWm2QgBR/bkwSWc+NpUFgNPN9PvQi8WEg5Um
      AGMCAwEAAaNjMGEwHQYDVR0OBBYEFDZh4QB8iAUJUYtEbEf/GkzJ6k8SMB8GA1Ud
      IwQYMBaAFDZh4QB8iAUJUYtEbEf/GkzJ6k8SMA8GA1UdEwEB/wQFMAMBAf8wDgYD
      VR0PAQH/BAQDAgIEMA0GCSqGSIb3DQEBCwUAA4ICAQB8cMqTllHc8U+qCrOlg3H7
      174lmaCsbo/bJ0C17JEgMLb4kvrqsXZs01U3mB/qABg/1t5Pd5AORHARs1hhqGIC
      W/nKMav574f9rZN4PC2ZlufGXb7sIdJpGiO9ctRhiLuYuly10JccUZGEHpHSYM2G
      tkgYbZba6lsCPYAAP83cyDV+1aOkTf1RCp/lM0PKvmxYN10RYsK631jrleGdcdkx
      oSK//mSQbgcWnmAEZrzHoF1/0gso1HZgIn0YLzVhLSA/iXCX4QT2h3J5z3znluKG
      1nv8NQdxei2DIIhASWfu804CA96cQKTTlaae2fweqXjdN1/v2nqOhngNyz1361mF
      mr4XmaKH/ItTwOe72NI9ZcwS1lVaCvsIkTDCEXdm9rCNPAY10iTunIHFXRh+7KPz
      lHGewCq/8TOohBRn0/NNfh7uRslOSZ/xKbN9tMBtw37Z8d2vvnXq/YWdsm1+JLVw
      n6yYD/yacNJBlwpddla8eaVMjsF6nBnIgQOf9zKSe06nSTqvgwUHosgOECZJZ1Eu
      zbH4yswbt02tKtKEFhx+v+OTge/06V+jGsqTWLsfrOCNLuA8H++z+pUENmpqnnHo
      vaI47gC+TNpkgYGkkBT6B/m/U01BuOBBTzhIlMEZq9qkDWuM2cA5kW5V3FJUcfHn
      w1IdYIg2Wxg7yHcQZemFQg==
      -----END CERTIFICATE-----
      """;

  /**
   * "Key Attestation CA1", Google LLC, EC P-384, issued 2025-07-17; signs attestation chains from
   * 2026-02-01. Same source as {@link #GOOGLE_ROOT_RSA_PEM}.
   */
  static final String GOOGLE_ROOT_EC_PEM = """
      -----BEGIN CERTIFICATE-----
      MIICIjCCAaigAwIBAgIRAISp0Cl7DrWK5/8OgN52BgUwCgYIKoZIzj0EAwMwUjEc
      MBoGA1UEAwwTS2V5IEF0dGVzdGF0aW9uIENBMTEQMA4GA1UECwwHQW5kcm9pZDET
      MBEGA1UECgwKR29vZ2xlIExMQzELMAkGA1UEBhMCVVMwHhcNMjUwNzE3MjIzMjE4
      WhcNMzUwNzE1MjIzMjE4WjBSMRwwGgYDVQQDDBNLZXkgQXR0ZXN0YXRpb24gQ0Ex
      MRAwDgYDVQQLDAdBbmRyb2lkMRMwEQYDVQQKDApHb29nbGUgTExDMQswCQYDVQQG
      EwJVUzB2MBAGByqGSM49AgEGBSuBBAAiA2IABCPaI3FO3z5bBQo8cuiEas4HjqCt
      G/mLFfRT0MsIssPBEEU5Cfbt6sH5yOAxqEi5QagpU1yX4HwnGb7OtBYpDTB57uH5
      Eczm34A5FNijV3s0/f0UPl7zbJcTx6xwqMIRq6NCMEAwDwYDVR0TAQH/BAUwAwEB
      /zAOBgNVHQ8BAf8EBAMCAQYwHQYDVR0OBBYEFFIyuyz7RkOb3NaBqQ5lZuA0QepA
      MAoGCCqGSM49BAMDA2gAMGUCMETfjPO/HwqReR2CS7p0ZWoD/LHs6hDi422opifH
      EUaYLxwGlT9SLdjkVpz0UUOR5wIxAIoGyxGKRHVTpqpGRFiJtQEOOTp/+s1GcxeY
      uR2zh/80lQyu9vAFCj6E4AXc+osmRg==
      -----END CERTIFICATE-----
      """;

  /** Public keys of the Google roots above, in that order. Extend the list when Google adds one. */
  public static final List<PublicKey> GOOGLE_ROOTS = List.of(
      rootKey(GOOGLE_ROOT_RSA_PEM), rootKey(GOOGLE_ROOT_EC_PEM));

  // ---- KeyMint constants (hardware/interfaces/security/keymint) --------------------------------

  private static final int SECURITY_LEVEL_SOFTWARE = 0;
  private static final int SECURITY_LEVEL_TEE = 1;
  private static final int SECURITY_LEVEL_STRONGBOX = 2;

  private static final int TAG_PURPOSE = 1;
  private static final int TAG_ALGORITHM = 2;
  private static final int TAG_EC_CURVE = 10;
  private static final int TAG_NO_AUTH_REQUIRED = 503;
  private static final int TAG_USER_AUTH_TYPE = 504;
  private static final int TAG_AUTH_TIMEOUT = 505;

  private static final int PURPOSE_SIGN = 2;
  private static final int ALGORITHM_EC = 3;
  private static final int EC_CURVE_P256 = 1;
  /** {@code HardwareAuthenticatorType.FINGERPRINT} (1 << 1); strong biometrics of any kind. */
  private static final long AUTH_TYPE_BIOMETRIC = 2;

  private final List<PublicKey> trustedRoots;

  /** Trusts the Google roots. */
  public KeyAttestation() {
    this(GOOGLE_ROOTS);
  }

  /** Trusts exactly {@code trustedRoots}; tests pass a root they made. */
  public KeyAttestation(List<PublicKey> trustedRoots) {
    this.trustedRoots = List.copyOf(Objects.requireNonNull(trustedRoots, "trustedRoots"));
  }

  /**
   * Verifies the chain; never throws.
   *
   * @param chainBase64 Base64 DER X.509 certificates, leaf first, as {@code
   *     KeyStore.getCertificateChain} returns them (the root may be included or omitted)
   * @param expectedChallenge the bytes the phone was told to put in {@code setAttestationChallenge};
   *     null skips the check
   * @param expectedPunchKeySpki Base64 DER SubjectPublicKeyInfo the pairing stores as its punch key
   */
  public Result verify(List<String> chainBase64, byte[] expectedChallenge,
      String expectedPunchKeySpki) {
    try {
      return check(chainBase64, expectedChallenge, expectedPunchKeySpki);
    } catch (RuntimeException e) {
      return Result.fail(Reason.ERROR);
    }
  }

  private Result check(List<String> chainBase64, byte[] expectedChallenge,
      String expectedPunchKeySpki) {
    if (chainBase64 == null || chainBase64.isEmpty()) {
      return Result.fail(Reason.CHAIN_EMPTY);
    }
    List<X509Certificate> chain = parseChain(chainBase64);
    if (chain == null) {
      return Result.fail(Reason.CHAIN_UNPARSEABLE);
    }
    for (int i = 0; i + 1 < chain.size(); i++) {
      if (!signedBy(chain.get(i), chain.get(i + 1).getPublicKey())) {
        return Result.fail(Reason.CHAIN_BROKEN);
      }
    }
    if (!endsInTrustedRoot(chain)) {
      return Result.fail(Reason.UNTRUSTED_ROOT);
    }

    // Only the first extension counted from the root is issued by the secure hardware; anything
    // below it could be an attacker extending the chain with their own key.
    X509Certificate attested = null;
    byte[] extension = null;
    for (int i = chain.size() - 1; i >= 0 && attested == null; i--) {
      byte[] value = chain.get(i).getExtensionValue(EXTENSION_OID);
      if (value != null) {
        attested = chain.get(i);
        extension = value;
      }
    }
    if (attested == null) {
      return Result.fail(Reason.NO_EXTENSION);
    }

    KeyDescription description;
    try {
      description = KeyDescription.parse(extension);
    } catch (IllegalArgumentException e) {
      return Result.fail(Reason.EXTENSION_MALFORMED);
    }
    String level = levelName(description.attestationSecurityLevel);
    if (description.attestationSecurityLevel != SECURITY_LEVEL_TEE
        && description.attestationSecurityLevel != SECURITY_LEVEL_STRONGBOX) {
      return Result.fail(Reason.SOFTWARE_LEVEL, level);
    }
    if (expectedChallenge != null
        && !Arrays.equals(expectedChallenge, description.attestationChallenge)) {
      return Result.fail(Reason.CHALLENGE_MISMATCH, level);
    }
    AuthorizationList hw = description.hardwareEnforced;
    if (!hw.purpose.contains(BigInteger.valueOf(PURPOSE_SIGN))) {
      return Result.fail(Reason.NOT_SIGN, level);
    }
    if (hw.algorithm == null || hw.algorithm.longValue() != ALGORITHM_EC) {
      return Result.fail(Reason.NOT_EC, level);
    }
    if (hw.ecCurve == null || hw.ecCurve.longValue() != EC_CURVE_P256) {
      return Result.fail(Reason.NOT_P256, level);
    }
    if (hw.noAuthRequired || hw.userAuthType == null
        || (hw.userAuthType.longValue() & AUTH_TYPE_BIOMETRIC) == 0) {
      return Result.fail(Reason.NO_BIOMETRIC, level);
    }
    if (hw.authTimeout != null && hw.authTimeout.longValue() > MAX_AUTH_TIMEOUT_SECONDS) {
      return Result.fail(Reason.AUTH_TIMEOUT, level);
    }

    byte[] expectedKey;
    try {
      expectedKey = AppSignatures.parseP256PublicKey(expectedPunchKeySpki).getEncoded();
    } catch (IllegalArgumentException | NullPointerException e) {
      return Result.fail(Reason.KEY_MISMATCH, level);
    }
    if (!Arrays.equals(expectedKey, attested.getPublicKey().getEncoded())) {
      return Result.fail(Reason.KEY_MISMATCH, level);
    }
    return new Result(true, level, Reason.OK);
  }

  // ---- chain ---------------------------------------------------------------------------------

  private static List<X509Certificate> parseChain(List<String> chainBase64) {
    if (chainBase64.size() > MAX_CHAIN_LENGTH) {
      return null;
    }
    List<X509Certificate> out = new ArrayList<>(chainBase64.size());
    try {
      CertificateFactory factory = CertificateFactory.getInstance("X.509");
      for (String b64 : chainBase64) {
        if (b64 == null || b64.length() > MAX_CERT_BASE64_CHARS) {
          return null;
        }
        byte[] der = Base64.getDecoder().decode(b64.trim());
        out.add((X509Certificate) factory.generateCertificate(new ByteArrayInputStream(der)));
      }
    } catch (CertificateException | IllegalArgumentException | ClassCastException e) {
      return null;
    }
    return out;
  }

  private static boolean signedBy(X509Certificate cert, PublicKey issuer) {
    try {
      cert.verify(issuer);
      return true;
    } catch (GeneralSecurityException | RuntimeException e) {
      return false;
    }
  }

  /** The last certificate is a trusted root itself, or is signed by one (root omitted). */
  private boolean endsInTrustedRoot(List<X509Certificate> chain) {
    X509Certificate last = chain.get(chain.size() - 1);
    byte[] lastKey = last.getPublicKey().getEncoded();
    for (PublicKey root : trustedRoots) {
      if (Arrays.equals(lastKey, root.getEncoded())) {
        return true;
      }
    }
    for (PublicKey root : trustedRoots) {
      if (signedBy(last, root)) {
        return true;
      }
    }
    return false;
  }

  private static PublicKey rootKey(String pem) {
    String base64 = pem.replace("-----BEGIN CERTIFICATE-----", "")
        .replace("-----END CERTIFICATE-----", "").replaceAll("\\s", "");
    try {
      X509Certificate cert = (X509Certificate) CertificateFactory.getInstance("X.509")
          .generateCertificate(new ByteArrayInputStream(Base64.getDecoder().decode(base64)));
      return cert.getPublicKey();
    } catch (CertificateException e) {
      throw new IllegalStateException("embedded attestation root does not parse", e);
    }
  }

  private static String levelName(int level) {
    return switch (level) {
      case SECURITY_LEVEL_SOFTWARE -> LEVEL_SOFTWARE;
      case SECURITY_LEVEL_TEE -> LEVEL_TRUSTED_ENVIRONMENT;
      case SECURITY_LEVEL_STRONGBOX -> LEVEL_STRONGBOX;
      default -> "Unknown(" + level + ")";
    };
  }

  // ---- KeyDescription ------------------------------------------------------------------------

  /**
   * The parts of {@code KeyDescription} the checks need.
   *
   * <pre>
   * KeyDescription ::= SEQUENCE {
   *   attestationVersion         INTEGER,
   *   attestationSecurityLevel   SecurityLevel,   -- ENUMERATED 0 | 1 | 2
   *   keyMintVersion             INTEGER,
   *   keyMintSecurityLevel       SecurityLevel,
   *   attestationChallenge       OCTET_STRING,
   *   uniqueId                   OCTET_STRING,
   *   softwareEnforced           AuthorizationList,
   *   hardwareEnforced           AuthorizationList }
   * </pre>
   */
  static final class KeyDescription {
    final int attestationSecurityLevel;
    final byte[] attestationChallenge;
    final AuthorizationList hardwareEnforced;

    private KeyDescription(int level, byte[] challenge, AuthorizationList hardwareEnforced) {
      this.attestationSecurityLevel = level;
      this.attestationChallenge = challenge;
      this.hardwareEnforced = hardwareEnforced;
    }

    /** @param extensionValue what {@code getExtensionValue} returns: an OCTET STRING wrapper */
    static KeyDescription parse(byte[] extensionValue) {
      Der.Tlv wrapper = Der.one(extensionValue);
      wrapper.requireUniversal(Der.OCTET_STRING);
      Der.Tlv sequence = Der.one(wrapper.content());
      sequence.requireUniversal(Der.SEQUENCE);
      List<Der.Tlv> fields = sequence.children();
      if (fields.size() < 8) {
        throw new IllegalArgumentException("KeyDescription has " + fields.size() + " fields");
      }
      fields.get(0).requireUniversal(Der.INTEGER);
      int level = fields.get(1).requireUniversal(Der.ENUMERATED).integer().intValueExact();
      fields.get(2).requireUniversal(Der.INTEGER);
      fields.get(3).requireUniversal(Der.ENUMERATED);
      byte[] challenge = fields.get(4).requireUniversal(Der.OCTET_STRING).content();
      fields.get(5).requireUniversal(Der.OCTET_STRING);
      fields.get(6).requireUniversal(Der.SEQUENCE);
      AuthorizationList hw = AuthorizationList.parse(fields.get(7).requireUniversal(Der.SEQUENCE));
      return new KeyDescription(level, challenge, hw);
    }
  }

  /**
   * The tags of {@code AuthorizationList} the checks read; every member is {@code [tag] EXPLICIT}
   * and optional. Unknown tags are skipped.
   */
  static final class AuthorizationList {
    final List<BigInteger> purpose = new ArrayList<>();
    BigInteger algorithm;
    BigInteger ecCurve;
    boolean noAuthRequired;
    BigInteger userAuthType;
    BigInteger authTimeout;

    static AuthorizationList parse(Der.Tlv sequence) {
      AuthorizationList list = new AuthorizationList();
      for (Der.Tlv member : sequence.children()) {
        if (member.tagClass != Der.CLASS_CONTEXT || !member.constructed) {
          throw new IllegalArgumentException("AuthorizationList member is not [n] EXPLICIT");
        }
        Der.Tlv value = Der.one(member.content());
        switch (member.tagNumber) {
          case TAG_PURPOSE -> {
            value.requireUniversal(Der.SET);
            for (Der.Tlv p : value.children()) {
              list.purpose.add(p.requireUniversal(Der.INTEGER).integer());
            }
          }
          case TAG_ALGORITHM -> list.algorithm = value.requireUniversal(Der.INTEGER).integer();
          case TAG_EC_CURVE -> list.ecCurve = value.requireUniversal(Der.INTEGER).integer();
          case TAG_NO_AUTH_REQUIRED -> {
            value.requireUniversal(Der.NULL);
            list.noAuthRequired = true;
          }
          case TAG_USER_AUTH_TYPE ->
              list.userAuthType = value.requireUniversal(Der.INTEGER).integer();
          case TAG_AUTH_TIMEOUT -> list.authTimeout = value.requireUniversal(Der.INTEGER).integer();
          default -> { }
        }
      }
      return list;
    }
  }

  // ---- DER walker ----------------------------------------------------------------------------

  /**
   * Just enough DER to walk a KeyDescription: definite lengths, high-tag-number form for the
   * {@code [503]}-style context tags, and the universal types the schema uses. Every malformed
   * input is an {@link IllegalArgumentException}.
   */
  static final class Der {
    static final int CLASS_UNIVERSAL = 0;
    static final int CLASS_CONTEXT = 2;

    static final int INTEGER = 2;
    static final int OCTET_STRING = 4;
    static final int NULL = 5;
    static final int ENUMERATED = 10;
    static final int SEQUENCE = 16;
    static final int SET = 17;

    private Der() {}

    /** One tag-length-value element. {@code start..end} is the content range in {@code buf}. */
    static final class Tlv {
      final byte[] buf;
      final int tagClass;
      final boolean constructed;
      final int tagNumber;
      final int start;
      final int end;
      /** Offset just after this element. */
      final int next;

      private Tlv(byte[] buf, int tagClass, boolean constructed, int tagNumber, int start,
          int end) {
        this.buf = buf;
        this.tagClass = tagClass;
        this.constructed = constructed;
        this.tagNumber = tagNumber;
        this.start = start;
        this.end = end;
        this.next = end;
      }

      byte[] content() {
        return Arrays.copyOfRange(buf, start, end);
      }

      Tlv requireUniversal(int tag) {
        if (tagClass != CLASS_UNIVERSAL || tagNumber != tag) {
          throw new IllegalArgumentException(
              "expected universal tag " + tag + " but found class " + tagClass + " tag " + tagNumber);
        }
        boolean shouldBeConstructed = tag == SEQUENCE || tag == SET;
        if (constructed != shouldBeConstructed) {
          throw new IllegalArgumentException("wrong constructed bit for tag " + tag);
        }
        return this;
      }

      /** Big-endian two's complement, as INTEGER and ENUMERATED are encoded. */
      BigInteger integer() {
        if (end == start) {
          throw new IllegalArgumentException("empty INTEGER");
        }
        return new BigInteger(content());
      }

      /** The elements inside a constructed value, in order. */
      List<Tlv> children() {
        if (!constructed) {
          throw new IllegalArgumentException("primitive element has no children");
        }
        List<Tlv> out = new ArrayList<>();
        int pos = start;
        while (pos < end) {
          Tlv child = read(buf, pos, end);
          out.add(child);
          pos = child.next;
        }
        return Collections.unmodifiableList(out);
      }
    }

    /** The single element that fills {@code bytes} exactly. */
    static Tlv one(byte[] bytes) {
      Tlv tlv = read(bytes, 0, bytes.length);
      if (tlv.next != bytes.length) {
        throw new IllegalArgumentException("trailing bytes after DER element");
      }
      return tlv;
    }

    static Tlv read(byte[] buf, int pos, int limit) {
      if (pos >= limit) {
        throw new IllegalArgumentException("DER element expected at " + pos);
      }
      int first = buf[pos++] & 0xFF;
      int tagClass = first >> 6;
      boolean constructed = (first & 0x20) != 0;
      int tagNumber = first & 0x1F;
      if (tagNumber == 0x1F) {
        tagNumber = 0;
        int count = 0;
        while (true) {
          if (pos >= limit || ++count > 4) {
            throw new IllegalArgumentException("bad high tag number");
          }
          int b = buf[pos++] & 0xFF;
          tagNumber = (tagNumber << 7) | (b & 0x7F);
          if ((b & 0x80) == 0) {
            break;
          }
        }
      }
      if (pos >= limit) {
        throw new IllegalArgumentException("DER length missing");
      }
      int lengthByte = buf[pos++] & 0xFF;
      int length;
      if (lengthByte < 0x80) {
        length = lengthByte;
      } else {
        int octets = lengthByte & 0x7F;
        if (octets == 0 || octets > 4) {
          throw new IllegalArgumentException("indefinite or oversized DER length");
        }
        length = 0;
        for (int i = 0; i < octets; i++) {
          if (pos >= limit) {
            throw new IllegalArgumentException("DER length truncated");
          }
          length = (length << 8) | (buf[pos++] & 0xFF);
        }
        if (length < 0) {
          throw new IllegalArgumentException("DER length overflow");
        }
      }
      if (length > limit - pos) {
        throw new IllegalArgumentException("DER content truncated");
      }
      return new Tlv(buf, tagClass, constructed, tagNumber, pos, pos + length);
    }
  }

  /** The bytes a claim token puts in the attestation challenge: its UTF-8 text. */
  public static byte[] challengeOf(String claimToken) {
    return claimToken == null ? null : claimToken.trim().getBytes(StandardCharsets.UTF_8);
  }
}
