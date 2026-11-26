package com.inventory.plan.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.inventory.common.exception.ValidationException;
import com.inventory.plan.rest.dto.request.CreatePlanCheckoutRequest;
import com.inventory.plan.rest.dto.request.QuoteRequest;
import com.inventory.plan.utils.CartUtils;
import com.inventory.plan.utils.constants.PricingConstants;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PlanValidatorCheckoutDurationTest {

  private final PlanValidator validator = new PlanValidator();

  @Test
  void acceptsYearlyAndOmittedDuration() {
    assertThatCode(() -> validator.validateCreateCheckoutRequest("shop-1", request(12), null))
        .doesNotThrowAnyException();
    assertThatCode(() -> validator.validateCreateCheckoutRequest("shop-1", request(null), null))
        .doesNotThrowAnyException();
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1, 6, 24, 120})
  void rejectsDurationsTheAnnualPriceDoesNotCover(int months) {
    assertThatThrownBy(() -> validator.validateCreateCheckoutRequest("shop-1", request(months), null))
        .isInstanceOf(ValidationException.class);
  }

  @Test
  void acceptsPlanCodeInsteadOfPlanId() {
    CreatePlanCheckoutRequest request = new CreatePlanCheckoutRequest();
    request.setPlanCode("GROWTH");
    assertThatCode(() -> validator.validateCreateCheckoutRequest("shop-1", request, "key-1"))
        .doesNotThrowAnyException();
  }

  @Test
  void rejectsMissingPlanAndBadIdempotencyKeys() {
    assertThatThrownBy(() -> validator.validateCreateCheckoutRequest("shop-1", new CreatePlanCheckoutRequest(), null))
        .isInstanceOf(ValidationException.class);
    assertThatThrownBy(() -> validator.validateCreateCheckoutRequest("shop-1", request(null), " "))
        .isInstanceOf(ValidationException.class);
    assertThatThrownBy(() -> validator.validateCreateCheckoutRequest("shop-1", request(null), "k".repeat(101)))
        .isInstanceOf(ValidationException.class);
  }

  @Test
  void acceptsAnAddOnOnlyCartWhateverItsDuration() {
    CreatePlanCheckoutRequest request = new CreatePlanCheckoutRequest();
    request.setAddOns(List.of(new QuoteRequest.AddOnLine("OCR_TOPUP_500", 1)));
    request.setDurationMonths(1);

    assertThatCode(() -> validator.validateCreateCheckoutRequest("shop-1", request, "key-1"))
        .doesNotThrowAnyException();
  }

  @Test
  void acceptsAVoucherOnlyCartWithNoPlanAsAnAddOnOnlyTopUp() {
    CreatePlanCheckoutRequest request = new CreatePlanCheckoutRequest();
    request.setVoucherCodes(List.of("MKT-FREE"));

    assertThatCode(() -> validator.validateCreateCheckoutRequest("shop-1", request, "key-1"))
        .doesNotThrowAnyException();
    assertThat(CartUtils.isAddOnOnly(request)).isTrue();
  }

  @Test
  void rejectsACartWithNeitherPlanNorAddOns() {
    CreatePlanCheckoutRequest request = new CreatePlanCheckoutRequest();
    request.setAddOns(List.of());

    assertThatThrownBy(() -> validator.validateCreateCheckoutRequest("shop-1", request, null))
        .isInstanceOf(ValidationException.class).hasMessage(PricingConstants.MSG_EMPTY_CART);
  }

  private static CreatePlanCheckoutRequest request(Integer months) {
    CreatePlanCheckoutRequest request = new CreatePlanCheckoutRequest();
    request.setPlanId("plan-1");
    request.setDurationMonths(months);
    return request;
  }
}
