package com.inventory.user.validation;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.inventory.common.exception.ValidationException;
import com.inventory.common.gst.PostalAddress;
import com.inventory.user.rest.dto.request.CreateVendorRequest;
import org.junit.jupiter.api.Test;

class VendorValidatorTest {

  final VendorValidator validator = new VendorValidator();

  private CreateVendorRequest base() {
    CreateVendorRequest r = new CreateVendorRequest();
    r.setName("Cipla");
    r.setContactPhone("9999999999");
    return r;
  }

  @Test
  void aValidGstinIsEnoughToPlaceTheVendor() {
    CreateVendorRequest r = base();
    r.setGstinUin("27aapfu0939f1zv");
    assertDoesNotThrow(() -> validator.validateCreateRequest(r));
  }

  @Test
  void aStateOnTheAddressIsEnoughToo() {
    CreateVendorRequest r = base();
    r.setPostalAddress(PostalAddress.builder().stateCode("10").build());
    assertDoesNotThrow(() -> validator.validateCreateRequest(r));
  }

  @Test
  void neitherIsRefusedWithAPlainMessage() {
    CreateVendorRequest r = base();
    r.setPostalAddress(PostalAddress.builder().line1("Main Road").build());
    ValidationException e = assertThrows(ValidationException.class, () -> validator.validateCreateRequest(r));
    assertTrue(e.getMessage().contains("GSTIN"));
    assertTrue(e.getMessage().contains("state"));
  }

  @Test
  void aMistypedGstinIsRefusedEvenWithAState() {
    CreateVendorRequest r = base();
    r.setGstinUin("27AAPFU0939F1ZW");
    r.setPostalAddress(PostalAddress.builder().stateCode("27").build());
    ValidationException e = assertThrows(ValidationException.class, () -> validator.validateCreateRequest(r));
    assertTrue(e.getMessage().startsWith("GSTIN:"));
  }

  @Test
  void anUnknownStateCodeIsRefused() {
    CreateVendorRequest r = base();
    r.setPostalAddress(PostalAddress.builder().stateCode("98").build());
    assertThrows(ValidationException.class, () -> validator.validateCreateRequest(r));
  }
}
