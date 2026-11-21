package com.inventory.hr.validation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.inventory.common.exception.ValidationException;
import com.inventory.hr.rest.dto.request.AppPunchRequest;
import com.inventory.hr.utils.AppSignatures.PunchFields;
import java.time.Instant;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class AppPunchValidatorTest {

  private final AppPunchValidator validator = new AppPunchValidator();

  private static AppPunchRequest valid() {
    AppPunchRequest r = new AppPunchRequest();
    r.setPairingId("7b2c3d4e5f6a7b8c9d0e1f2a");
    r.setShopId("5f1a2b3c4d5e6f7a8b9c0d1e");
    r.setEmployeeId("6a1b2c3d4e5f6a7b8c9d0e1f");
    r.setPunchId("3f2504e0-4f89-41d3-9a0c-0305e82c3301");
    r.setPhoneTime("2025-04-08T03:42:00Z");
    r.setOffline(false);
    r.setLocationChecked(true);
    r.setDistanceMetres(12);
    r.setAccuracyMetres(5);
    r.setMock(false);
    r.setSignature(Base64.getEncoder().encodeToString(new byte[70]));
    return r;
  }

  private String message(AppPunchRequest r) {
    return assertThrows(ValidationException.class, () -> validator.validate(r)).getMessage();
  }

  @Test
  void aWellFormedRequestBecomesThePayloadFieldsInOrder() {
    PunchFields f = validator.validate(valid());

    assertEquals("7b2c3d4e5f6a7b8c9d0e1f2a", f.pairingId());
    assertEquals("3f2504e0-4f89-41d3-9a0c-0305e82c3301", f.punchId());
    assertEquals(Instant.parse("2025-04-08T03:42:00Z"), f.phoneTime());
    assertTrue(f.locationChecked());
    assertEquals(12, f.distanceMetres());
    assertEquals(5, f.accuracyMetres());
  }

  @Test
  void idsMustBeLowercaseHex() {
    AppPunchRequest r = valid();
    r.setShopId("5F1A2B3C4D5E6F7A8B9C0D1E");
    assertTrue(message(r).startsWith("shopId"));

    r = valid();
    r.setPunchId("3F2504E0-4F89-41D3-9A0C-0305E82C3301");
    assertTrue(message(r).startsWith("punchId"));
  }

  @Test
  void phoneTimeMustBeUtcWholeSeconds() {
    AppPunchRequest r = valid();
    r.setPhoneTime("2025-04-08T03:42:00.500Z");
    assertTrue(message(r).startsWith("phoneTime"));

    r = valid();
    r.setPhoneTime("2025-04-08T09:12:00+05:30");
    assertTrue(message(r).startsWith("phoneTime"));

    r = valid();
    r.setPhoneTime(null);
    assertTrue(message(r).startsWith("phoneTime"));
  }

  @Test
  void flagsAreRequiredAndMetresMustNotBeNegative() {
    AppPunchRequest r = valid();
    r.setMock(null);
    assertTrue(message(r).startsWith("mock"));

    r = valid();
    r.setDistanceMetres(-1);
    assertTrue(message(r).startsWith("distanceMetres"));

    r = valid();
    r.setDistanceMetres(null);
    r.setAccuracyMetres(null);
    validator.validate(r);
  }

  @Test
  void theSignatureMustBeBase64AndBounded() {
    AppPunchRequest r = valid();
    r.setSignature("not base64!");
    assertTrue(message(r).startsWith("signature"));

    r = valid();
    r.setSignature("A".repeat(200));
    assertTrue(message(r).startsWith("signature"));

    r = valid();
    r.setSignature(null);
    assertTrue(message(r).startsWith("signature"));
  }
}
