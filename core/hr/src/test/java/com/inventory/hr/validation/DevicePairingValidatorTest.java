package com.inventory.hr.validation;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.inventory.common.exception.ValidationException;
import com.inventory.hr.domain.model.enums.DeviceOs;
import java.security.GeneralSecurityException;
import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class DevicePairingValidatorTest {

  private static final String OBJECT_ID = "6630a1b2c3d4e5f60718293a";
  private static final String UUID = "7c9e6679-7425-40de-944b-e07fc1f90ae7";

  private final DevicePairingValidator validator = new DevicePairingValidator();

  private static String spki(String curve) throws GeneralSecurityException {
    KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
    g.initialize(new ECGenParameterSpec(curve));
    return Base64.getEncoder().encodeToString(g.generateKeyPair().getPublic().getEncoded());
  }

  @Test
  void anObjectIdIs24HexCharacters() {
    assertDoesNotThrow(() -> validator.requireObjectId(OBJECT_ID, "pairingId"));
    assertDoesNotThrow(() -> validator.requireObjectId(" " + OBJECT_ID.toUpperCase() + " ", "x"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "   ", "emp-1", "6630a1b2c3d4e5f60718293", "6630a1b2c3d4e5f60718293ab",
      "6630a1b2c3d4e5f60718293g"})
  void anythingElseIsNotAnObjectId(String value) {
    ValidationException e = assertThrows(ValidationException.class,
        () -> validator.requireObjectId(value, "pairingId"));
    assertTrue(e.getMessage().startsWith("pairingId"));
  }

  @Test
  void aNullIdIsRejectedWithTheFieldName() {
    ValidationException e = assertThrows(ValidationException.class,
        () -> validator.requireObjectId(null, "employeeId"));
    assertTrue(e.getMessage().startsWith("employeeId"));
  }

  @Test
  void anInstallationIdIsAUuid() {
    assertDoesNotThrow(() -> validator.requireInstallationId(UUID));
    assertDoesNotThrow(() -> validator.requireInstallationId(UUID.toUpperCase()));
    assertThrows(ValidationException.class, () -> validator.requireInstallationId(null));
    assertThrows(ValidationException.class, () -> validator.requireInstallationId("not-a-uuid"));
    assertThrows(ValidationException.class,
        () -> validator.requireInstallationId("7c9e667974254 0de944be07fc1f90ae7"));
  }

  @Test
  void aP256PublicKeyParses() throws GeneralSecurityException {
    assertDoesNotThrow(() -> validator.requireP256PublicKey(spki("secp256r1"), "punchPublicKey"));
  }

  @Test
  void aKeyOnAnotherCurveOrGarbageIsRejectedWithoutEchoingIt() throws GeneralSecurityException {
    String p384 = spki("secp384r1");
    ValidationException e = assertThrows(ValidationException.class,
        () -> validator.requireP256PublicKey(p384, "punchPublicKey"));
    assertFalse(e.getMessage().contains(p384));
    assertTrue(e.getMessage().startsWith("punchPublicKey"));

    assertThrows(ValidationException.class,
        () -> validator.requireP256PublicKey("not base64!", "devicePublicKey"));
    assertThrows(ValidationException.class,
        () -> validator.requireP256PublicKey(Base64.getEncoder().encodeToString(new byte[40]),
            "devicePublicKey"));
    assertThrows(ValidationException.class, () -> validator.requireP256PublicKey("", "k"));
    assertThrows(ValidationException.class, () -> validator.requireP256PublicKey(null, "k"));
  }

  @Test
  void thePhoneModelIsOptionalSingleLineAndAtMost80Characters() {
    assertDoesNotThrow(() -> validator.requirePhoneModel(null));
    assertDoesNotThrow(() -> validator.requirePhoneModel("Redmi Note 11"));
    assertDoesNotThrow(() -> validator.requirePhoneModel("x".repeat(80)));
    assertThrows(ValidationException.class, () -> validator.requirePhoneModel("x".repeat(81)));
    assertThrows(ValidationException.class, () -> validator.requirePhoneModel("Redmi\nNote"));
  }

  @Test
  void osAndVersionsAreBounded() {
    assertDoesNotThrow(() -> validator.requireOs(DeviceOs.ANDROID));
    assertThrows(ValidationException.class, () -> validator.requireOs(null));
    assertDoesNotThrow(() -> validator.requireOsVersion("13"));
    assertThrows(ValidationException.class, () -> validator.requireOsVersion("1".repeat(41)));
    assertDoesNotThrow(() -> validator.requireAppVersion("1.0.0"));
    assertThrows(ValidationException.class, () -> validator.requireAppVersion("1".repeat(41)));
  }

  @Test
  void claimTokenAndConsentVersionShapes() {
    assertDoesNotThrow(() -> validator.requireClaimToken("a".repeat(43)));
    assertThrows(ValidationException.class, () -> validator.requireClaimToken(" "));
    assertThrows(ValidationException.class, () -> validator.requireClaimToken("a".repeat(129)));
    assertDoesNotThrow(() -> validator.requireConsentVersion(1));
    assertThrows(ValidationException.class, () -> validator.requireConsentVersion(0));
    assertThrows(ValidationException.class, () -> validator.requireConsentVersion(null));
  }
}
