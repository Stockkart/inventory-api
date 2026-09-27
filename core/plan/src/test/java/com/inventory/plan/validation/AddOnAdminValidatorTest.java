package com.inventory.plan.validation;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.inventory.common.entitlement.PlanFeature;
import com.inventory.common.exception.ValidationException;
import com.inventory.plan.domain.model.AddOnBillingType;
import com.inventory.plan.domain.model.AddOnGrantType;
import com.inventory.plan.rest.dto.request.AddOnAdminRequest;
import com.inventory.plan.rest.dto.request.AddOnGrantRequest;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class AddOnAdminValidatorTest {

  private final AddOnAdminValidator validator = new AddOnAdminValidator();

  @Test
  void acceptsFeatureSeatAndCreditAddOns() {
    assertThatCode(() -> validator.validateCreate(feature().build())).doesNotThrowAnyException();
    assertThatCode(() -> validator.validateCreate(seats().build())).doesNotThrowAnyException();
    assertThatCode(() -> validator.validateCreate(AddOnAdminRequest.builder().code("OCR_TOPUP_500").name("500 scans")
        .price(new BigDecimal("199")).billingType(AddOnBillingType.ONE_TIME).grantType(AddOnGrantType.OCR_CREDITS)
        .grantsQuantity(500).stackable(true).build())).doesNotThrowAnyException();
  }

  @Test
  void rejectsInconsistentAddOns() {
    assertInvalid(feature().code("bad code").build());
    assertInvalid(feature().price(BigDecimal.ZERO).build());
    assertInvalid(feature().grantsFeature(null).build());
    assertInvalid(feature().stackable(true).build());
    assertInvalid(feature().billingType(AddOnBillingType.ONE_TIME).build());
    assertInvalid(seats().grantsQuantity(0).build());
    assertInvalid(seats().grantsFeature(PlanFeature.MARKETING).build());
    assertInvalid(seats().maxQuantity(0).build());
    assertInvalid(seats().grantType(null).build());
  }

  @Test
  void grantTypeIsFixedAfterCreate() {
    assertThatThrownBy(() -> validator.validateUpdate("id-1", seats().build(), AddOnGrantType.FEATURE))
        .isInstanceOf(ValidationException.class);
    assertThatCode(() -> validator.validateUpdate("id-1", seats().grantType(null).build(), AddOnGrantType.SEATS))
        .doesNotThrowAnyException();
  }

  @Test
  void manualGrantNeedsShopCodeAndReason() {
    assertThatCode(() -> validator.validateGrant(AddOnGrantRequest.builder().shopId("s").addOnCode("A").reason("goodwill").build()))
        .doesNotThrowAnyException();
    assertThatThrownBy(() -> validator.validateGrant(AddOnGrantRequest.builder().shopId("s").addOnCode("A").build()))
        .isInstanceOf(ValidationException.class);
    assertThatThrownBy(() -> validator.validateGrant(AddOnGrantRequest.builder().addOnCode("A").reason("r").build()))
        .isInstanceOf(ValidationException.class);
    assertThatThrownBy(() -> validator.validateGrant(AddOnGrantRequest.builder().shopId("s").addOnCode("A").reason("r").quantity(0).build()))
        .isInstanceOf(ValidationException.class);
  }

  private void assertInvalid(AddOnAdminRequest request) {
    assertThatThrownBy(() -> validator.validateCreate(request)).isInstanceOf(ValidationException.class);
  }

  private static AddOnAdminRequest.AddOnAdminRequestBuilder feature() {
    return AddOnAdminRequest.builder().code("MARKETING_MODULE").name("Marketing").price(new BigDecimal("1999"))
        .billingType(AddOnBillingType.ANNUAL).grantType(AddOnGrantType.FEATURE).grantsFeature(PlanFeature.MARKETING);
  }

  private static AddOnAdminRequest.AddOnAdminRequestBuilder seats() {
    return AddOnAdminRequest.builder().code("ADDITIONAL_USER").name("Additional user").price(new BigDecimal("500"))
        .billingType(AddOnBillingType.ANNUAL).grantType(AddOnGrantType.SEATS).grantsQuantity(1).stackable(true);
  }
}
