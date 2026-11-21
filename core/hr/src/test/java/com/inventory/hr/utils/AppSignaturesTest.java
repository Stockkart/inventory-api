package com.inventory.hr.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.inventory.hr.utils.AppSignatures.EnvelopeFields;
import com.inventory.hr.utils.AppSignatures.PunchFields;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.time.Instant;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class AppSignaturesTest {

  private static final String PAIRING_ID = "67a1b2c3d4e5f60718293a4b";
  private static final String SHOP_ID = "5f1d7c9e8b3a4f2e1d0c9b8a";
  private static final String EMPLOYEE_ID = "64f0e1d2c3b4a59687766554";
  private static final String PUNCH_ID = "3f2504e0-4f89-41d3-9a0c-0305e82c3301";

  private static KeyPair keyPair(String curve) throws GeneralSecurityException {
    KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
    g.initialize(new ECGenParameterSpec(curve));
    return g.generateKeyPair();
  }

  private static String spki(KeyPair kp) {
    return Base64.getEncoder().encodeToString(kp.getPublic().getEncoded());
  }

  private static String sign(KeyPair kp, byte[] payload) throws GeneralSecurityException {
    Signature s = Signature.getInstance("SHA256withECDSA");
    s.initSign(kp.getPrivate());
    s.update(payload);
    return Base64.getEncoder().encodeToString(s.sign());
  }

  private static PunchFields punch(Integer distance, Integer accuracy, boolean mock) {
    return new PunchFields(
        PAIRING_ID,
        SHOP_ID,
        EMPLOYEE_ID,
        PUNCH_ID,
        Instant.parse("2025-04-03T03:42:07Z"),
        false,
        true,
        distance,
        accuracy,
        mock);
  }

  @Test
  void punchPayloadIsTheTenLineProtocolWithoutTrailingNewline() {
    String text = new String(AppSignatures.punchPayload(punch(42, 12, false)), StandardCharsets.UTF_8);
    assertEquals(
        "SKAT-PUNCH-1\n"
            + "pairingId=67a1b2c3d4e5f60718293a4b\n"
            + "shopId=5f1d7c9e8b3a4f2e1d0c9b8a\n"
            + "employeeId=64f0e1d2c3b4a59687766554\n"
            + "punchId=3f2504e0-4f89-41d3-9a0c-0305e82c3301\n"
            + "phoneTime=2025-04-03T03:42:07Z\n"
            + "offline=0\n"
            + "locationChecked=1\n"
            + "distanceMetres=42\n"
            + "accuracyMetres=12\n"
            + "mock=0",
        text);
  }

  @Test
  void missingMetresAreWrittenAsADash() {
    String text = new String(AppSignatures.punchPayload(punch(null, null, true)), StandardCharsets.UTF_8);
    assertTrue(text.contains("\ndistanceMetres=-\naccuracyMetres=-\nmock=1"));
  }

  @Test
  void punchPayloadRejectsIllShapedFields() {
    assertThrows(
        IllegalArgumentException.class,
        () -> AppSignatures.punchPayload(
            new PunchFields(
                "ABC", SHOP_ID, EMPLOYEE_ID, PUNCH_ID, Instant.EPOCH, false, false, null, null, false)));
    assertThrows(
        IllegalArgumentException.class,
        () -> AppSignatures.punchPayload(
            new PunchFields(
                PAIRING_ID, SHOP_ID, EMPLOYEE_ID, PUNCH_ID.toUpperCase(), Instant.EPOCH, false, false,
                null, null, false)));
    assertThrows(
        IllegalArgumentException.class,
        () -> AppSignatures.punchPayload(
            new PunchFields(
                PAIRING_ID, SHOP_ID, EMPLOYEE_ID, PUNCH_ID, Instant.parse("2025-04-03T03:42:07.500Z"),
                false, false, null, null, false)));
    assertThrows(IllegalArgumentException.class, () -> AppSignatures.punchPayload(punch(-1, 5, false)));
  }

  @Test
  void requestEnvelopeHashesTheBodyAndTreatsNoBodyAsEmpty() {
    byte[] body = "{\"version\":2}".getBytes(StandardCharsets.UTF_8);
    String text =
        new String(
            AppSignatures.requestEnvelope(
                new EnvelopeFields(
                    PAIRING_ID,
                    "POST",
                    "/api/v1/hr/attendance-app/consent",
                    null,
                    1743651727L,
                    "a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d",
                    body)),
            StandardCharsets.UTF_8);
    assertEquals(
        "SKAT-REQ-1\n"
            + "pairingId=67a1b2c3d4e5f60718293a4b\n"
            + "method=POST\n"
            + "path=/api/v1/hr/attendance-app/consent\n"
            + "query=\n"
            + "timestamp=1743651727\n"
            + "nonce=a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d\n"
            + "bodySha256="
            + AppSignatures.sha256Hex(body),
        text);

    String noBody =
        new String(
            AppSignatures.requestEnvelope(
                new EnvelopeFields(
                    PAIRING_ID, "GET", "/api/v1/hr/attendance-app/me", "", 1L,
                    "a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d", null)),
            StandardCharsets.UTF_8);
    assertTrue(
        noBody.endsWith(
            "bodySha256=e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"));
  }

  @Test
  void requestEnvelopeRejectsOtherMethodsAndPathsWithQueryStrings() {
    assertThrows(
        IllegalArgumentException.class,
        () -> AppSignatures.requestEnvelope(
            new EnvelopeFields(PAIRING_ID, "PUT", "/x", "", 1L, "a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d", null)));
    assertThrows(
        IllegalArgumentException.class,
        () -> AppSignatures.requestEnvelope(
            new EnvelopeFields(PAIRING_ID, "GET", "/x?a=1", "", 1L, "a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d", null)));
  }

  @Test
  void sha256HexMatchesTheKnownVector() {
    assertEquals(
        "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
        AppSignatures.sha256Hex("abc".getBytes(StandardCharsets.UTF_8)));
  }

  @Test
  void verifyAcceptsASignatureFromTheMatchingP256Key() throws GeneralSecurityException {
    KeyPair kp = keyPair("secp256r1");
    byte[] payload = AppSignatures.punchPayload(punch(42, 12, false));
    assertTrue(AppSignatures.verify(spki(kp), payload, sign(kp, payload)));
  }

  @Test
  void verifyRefusesTamperingAndOtherKeys() throws GeneralSecurityException {
    KeyPair kp = keyPair("secp256r1");
    byte[] payload = AppSignatures.punchPayload(punch(42, 12, false));
    String signature = sign(kp, payload);

    byte[] tampered = AppSignatures.punchPayload(punch(43, 12, false));
    assertFalse(AppSignatures.verify(spki(kp), tampered, signature));

    KeyPair other = keyPair("secp256r1");
    assertFalse(AppSignatures.verify(spki(other), payload, signature));

    assertFalse(AppSignatures.verify(spki(kp), payload, "not base64!"));
    assertFalse(AppSignatures.verify(spki(kp), payload, Base64.getEncoder().encodeToString(new byte[8])));
    assertFalse(AppSignatures.verify("", payload, signature));
    assertFalse(AppSignatures.verify(null, payload, signature));
  }

  @Test
  void keysNotOnSecp256r1AreRejected() throws GeneralSecurityException {
    KeyPair p384 = keyPair("secp384r1");
    byte[] payload = AppSignatures.punchPayload(punch(42, 12, false));
    assertThrows(IllegalArgumentException.class, () -> AppSignatures.parseP256PublicKey(spki(p384)));
    assertFalse(AppSignatures.verify(spki(p384), payload, sign(p384, payload)));

    assertThrows(IllegalArgumentException.class, () -> AppSignatures.parseP256PublicKey("AAAA"));
    assertThrows(IllegalArgumentException.class, () -> AppSignatures.parseP256PublicKey("%%%"));
  }
}
