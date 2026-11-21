package com.inventory.hr.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.inventory.hr.utils.KeyAttestation.Reason;
import com.inventory.hr.utils.KeyAttestation.Result;
import com.inventory.hr.utils.TestAttestationChains.Options;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.interfaces.ECPublicKey;
import java.security.interfaces.RSAPublicKey;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * {@link KeyAttestation} against synthetic chains from {@link TestAttestationChains} (task 16.4).
 * The Google roots are only checked for parsing; a real device chain cannot be made in a test.
 */
class KeyAttestationTest {

  private static final byte[] CHALLENGE = "claim-token".getBytes(StandardCharsets.UTF_8);

  private static TestAttestationChains chains;
  private static KeyAttestation verifier;
  private static KeyPair punch;
  private static String punchSpki;

  @BeforeAll
  static void fixtures() throws GeneralSecurityException {
    chains = new TestAttestationChains();
    verifier = new KeyAttestation(List.of(chains.rootKey()));
    punch = TestAttestationChains.p256();
    punchSpki = TestAttestationChains.spki(punch.getPublic());
  }

  private Result verify(List<String> chain) {
    return verifier.verify(chain, CHALLENGE, punchSpki);
  }

  // ---- ok ------------------------------------------------------------------------------------

  @Test
  void aGoodTeeChainVerifies() throws Exception {
    Result r = verify(chains.chain(punch.getPublic(), new Options()));
    assertEquals(new Result(true, KeyAttestation.LEVEL_TRUSTED_ENVIRONMENT, Reason.OK), r);
  }

  @Test
  void aGoodStrongBoxChainVerifiesAndReportsTheLevel() throws Exception {
    Result r = verify(chains.chain(punch.getPublic(),
        new Options().level(TestAttestationChains.LEVEL_STRONGBOX)));
    assertTrue(r.ok());
    assertEquals(KeyAttestation.LEVEL_STRONGBOX, r.securityLevel());
  }

  @Test
  void theRootMayBeOmittedFromTheChain() throws Exception {
    assertTrue(verify(chains.chainWithoutRoot(punch.getPublic(), new Options())).ok());
  }

  @Test
  void aShortAuthTimeoutAndAPasswordBitAlongsideBiometricAreAccepted() throws Exception {
    // 30 s is the ceiling; "biometric or password" still includes a biometric
    Result r = verify(chains.chain(punch.getPublic(), new Options().authTimeout(30)
        .userAuthType(TestAttestationChains.AUTH_BIOMETRIC | TestAttestationChains.AUTH_PASSWORD)));
    assertTrue(r.ok(), r.reason());
  }

  @Test
  void aNullChallengeSkipsTheChallengeCheck() throws Exception {
    List<String> chain = chains.chain(punch.getPublic(),
        new Options().challenge("something-else".getBytes(StandardCharsets.UTF_8)));
    assertTrue(verifier.verify(chain, null, punchSpki).ok());
  }

  // ---- failures ------------------------------------------------------------------------------

  @Test
  void wrongCurveIsRefused() throws Exception {
    Result r = verify(chains.chain(punch.getPublic(),
        new Options().ecCurve(TestAttestationChains.EC_CURVE_P384)));
    assertEquals(Reason.NOT_P256, r.reason());
    assertFalse(r.ok());
    assertEquals(KeyAttestation.LEVEL_TRUSTED_ENVIRONMENT, r.securityLevel(),
        "the level is still reported so the dashboard can split failures");
  }

  @Test
  void noBiometricIsRefused() throws Exception {
    assertEquals(Reason.NO_BIOMETRIC, verify(chains.chain(punch.getPublic(),
        new Options().userAuthType(TestAttestationChains.AUTH_PASSWORD))).reason());
    assertEquals(Reason.NO_BIOMETRIC, verify(chains.chain(punch.getPublic(),
        new Options().noAuthRequired())).reason());
    assertEquals(Reason.NO_BIOMETRIC, verify(chains.chain(punch.getPublic(),
        new Options().userAuthType(null))).reason());
  }

  @Test
  void aLongAuthTimeoutIsRefused() throws Exception {
    assertEquals(Reason.AUTH_TIMEOUT,
        verify(chains.chain(punch.getPublic(), new Options().authTimeout(31))).reason());
  }

  @Test
  void softwareLevelIsRefused() throws Exception {
    Result r = verify(chains.chain(punch.getPublic(),
        new Options().level(TestAttestationChains.LEVEL_SOFTWARE)));
    assertEquals(Reason.SOFTWARE_LEVEL, r.reason());
    assertEquals(KeyAttestation.LEVEL_SOFTWARE, r.securityLevel());
  }

  @Test
  void anUntrustedRootIsRefused() throws Exception {
    List<String> other = TestAttestationChains.chainToOtherRoot(punch.getPublic(), new Options());
    assertEquals(Reason.UNTRUSTED_ROOT, verify(other).reason());
    // nor does the production verifier (Google roots) trust a test root
    assertEquals(Reason.UNTRUSTED_ROOT,
        new KeyAttestation().verify(other, CHALLENGE, punchSpki).reason());
  }

  @Test
  void aBrokenSignatureInTheChainIsRefused() throws Exception {
    assertEquals(Reason.CHAIN_BROKEN,
        verify(chains.brokenChain(punch.getPublic(), new Options())).reason());
  }

  @Test
  void keyMismatchIsRefused() throws Exception {
    KeyPair other = TestAttestationChains.p256();
    List<String> chain = chains.chain(other.getPublic(), new Options());
    assertEquals(Reason.KEY_MISMATCH, verify(chain).reason());
    assertEquals(Reason.KEY_MISMATCH,
        verifier.verify(chain, CHALLENGE, "not a key").reason());
  }

  @Test
  void aWrongChallengeIsRefused() throws Exception {
    Result r = verify(chains.chain(punch.getPublic(),
        new Options().challenge("another-token".getBytes(StandardCharsets.UTF_8))));
    assertEquals(Reason.CHALLENGE_MISMATCH, r.reason());
  }

  @Test
  void wrongPurposeOrAlgorithmIsRefused() throws Exception {
    assertEquals(Reason.NOT_SIGN, verify(chains.chain(punch.getPublic(),
        new Options().purpose(TestAttestationChains.PURPOSE_VERIFY))).reason());
    assertEquals(Reason.NOT_EC, verify(chains.chain(punch.getPublic(),
        new Options().algorithm(TestAttestationChains.ALGORITHM_RSA))).reason());
  }

  @Test
  void aChainWithoutTheExtensionIsRefused() throws Exception {
    assertEquals(Reason.NO_EXTENSION,
        verify(chains.chain(punch.getPublic(), new Options().withoutExtension())).reason());
  }

  @Test
  void emptyOrGarbageChainsNeverThrow() throws Exception {
    assertEquals(Reason.CHAIN_EMPTY, verify(null).reason());
    assertEquals(Reason.CHAIN_EMPTY, verify(List.of()).reason());
    assertEquals(Reason.CHAIN_UNPARSEABLE, verify(List.of("not base64!")).reason());
    assertEquals(Reason.CHAIN_UNPARSEABLE,
        verify(List.of(Base64.getEncoder().encodeToString(new byte[] {1, 2, 3}))).reason());
    List<String> tooMany = new ArrayList<>();
    List<String> good = chains.chain(punch.getPublic(), new Options());
    for (int i = 0; i <= KeyAttestation.MAX_CHAIN_LENGTH; i++) {
      tooMany.add(good.get(0));
    }
    assertEquals(Reason.CHAIN_UNPARSEABLE, verify(tooMany).reason());
    List<String> oversized = new ArrayList<>(good);
    oversized.set(0, "A".repeat(KeyAttestation.MAX_CERT_BASE64_CHARS + 4));
    assertEquals(Reason.CHAIN_UNPARSEABLE, verify(oversized).reason());
  }

  @Test
  void aMalformedExtensionIsRefusedNotThrown() {
    byte[] description = TestAttestationChains.keyDescription(new Options());
    // truncating the KeyDescription makes the DER walker give up
    byte[] truncated = Arrays.copyOf(description, description.length - 5);
    assertThrows(IllegalArgumentException.class,
        () -> KeyAttestation.KeyDescription.parse(
            TestAttestationChains.Der.octetString(truncated)));
    // and the walker reads a well-formed one back
    KeyAttestation.KeyDescription parsed =
        KeyAttestation.KeyDescription.parse(TestAttestationChains.Der.octetString(description));
    assertEquals(1, parsed.attestationSecurityLevel);
    assertEquals(List.of(BigInteger.TWO), parsed.hardwareEnforced.purpose);
    assertEquals(2, parsed.hardwareEnforced.userAuthType.intValue());
    assertNull(parsed.hardwareEnforced.authTimeout);
  }

  // ---- embedded roots ------------------------------------------------------------------------

  @Test
  void theGoogleRootsParseAndAreAnRsa4096AndAP384Key() {
    assertEquals(2, KeyAttestation.GOOGLE_ROOTS.size());
    assertTrue(KeyAttestation.GOOGLE_ROOTS.get(0) instanceof RSAPublicKey rsa
        && rsa.getModulus().bitLength() == 4096);
    assertTrue(KeyAttestation.GOOGLE_ROOTS.get(1) instanceof ECPublicKey ec
        && ec.getParams().getCurve().getField().getFieldSize() == 384);
  }
}
