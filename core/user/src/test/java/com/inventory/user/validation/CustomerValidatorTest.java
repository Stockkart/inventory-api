package com.inventory.user.validation;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.inventory.common.exception.ValidationException;
import com.inventory.common.gst.PostalAddress;
import com.inventory.user.rest.dto.request.UpdateCustomerRequest;
import com.inventory.user.domain.model.enums.CustomerPartyType;
import com.inventory.user.rest.dto.request.CreateCustomerRequest;
import org.junit.jupiter.api.Test;

class CustomerValidatorTest {

  private final CustomerValidator validator = new CustomerValidator();

  @Test
  void createRequiresUniqueIdentifier() {
    CreateCustomerRequest request = new CreateCustomerRequest();
    request.setName("Walk-in");
    request.setAddress("Street 1");
    assertThrows(ValidationException.class, () -> validator.validateCreateRequest(request));
  }

  @Test
  void createAcceptsPhoneAsUniqueKey() {
    CreateCustomerRequest request = new CreateCustomerRequest();
    request.setName("Alice");
    request.setPhone("9876543210");
    assertDoesNotThrow(() -> validator.validateCreateRequest(request));
  }

  @Test
  void retailerRequiresUniqueKey() {
    assertTrue(
        CustomerValidator.hasUniqueIdentifier("1", null, null, null, null));
    assertFalse(
        CustomerValidator.hasUniqueIdentifier(null, null, null, null, null));
    CreateCustomerRequest request = new CreateCustomerRequest();
    request.setName("Shop");
    request.setPartyType(CustomerPartyType.RETAILER);
    request.setPhone("9999999999");
    assertDoesNotThrow(() -> validator.validateCreateRequest(request));
  }

  @Test
  void aValidGstinIsAccepted() {
    CreateCustomerRequest request = new CreateCustomerRequest();
    request.setName("Shop");
    request.setGstin("27AAPFU0939F1ZV");
    assertDoesNotThrow(() -> validator.validateCreateRequest(request));
  }

  @Test
  void aMistypedGstinIsRejectedOffline() {
    CreateCustomerRequest request = new CreateCustomerRequest();
    request.setName("Shop");
    request.setPhone("9876543210");
    request.setGstin("27AAPFU0939F1ZW");
    ValidationException ex =
        assertThrows(ValidationException.class, () -> validator.validateCreateRequest(request));
    assertTrue(ex.getMessage().startsWith("GSTIN:"));
  }

  @Test
  void customersDoNotNeedAStateOnTheirAddress() {
    CreateCustomerRequest request = new CreateCustomerRequest();
    request.setName("Walk-in Bob");
    request.setPhone("9876543210");
    request.setPostalAddress(PostalAddress.builder().city("Patna").build());
    assertDoesNotThrow(() -> validator.validateCreateRequest(request));
  }

  @Test
  void anUnknownStateCodeIsRejected() {
    UpdateCustomerRequest request = new UpdateCustomerRequest();
    request.setPostalAddress(PostalAddress.builder().stateCode("00").build());
    assertThrows(ValidationException.class, () -> validator.validateUpdateRequest(request));
    request.setPostalAddress(PostalAddress.builder().stateCode("10").build());
    assertDoesNotThrow(() -> validator.validateUpdateRequest(request));
  }
}
