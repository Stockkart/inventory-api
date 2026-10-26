package com.inventory.plan.validation;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.inventory.common.exception.ValidationException;
import com.inventory.plan.rest.dto.request.CreatePlanCheckoutRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PlanValidatorCheckoutDurationTest {

  private final PlanValidator validator = new PlanValidator();

  @Test
  void acceptsYearlyAndOmittedDuration() {
    assertThatCode(() -> validator.validateCreateCheckoutRequest("shop-1", request(12)))
        .doesNotThrowAnyException();
    assertThatCode(() -> validator.validateCreateCheckoutRequest("shop-1", request(null)))
        .doesNotThrowAnyException();
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1, 6, 24, 120})
  void rejectsDurationsTheAnnualPriceDoesNotCover(int months) {
    assertThatThrownBy(() -> validator.validateCreateCheckoutRequest("shop-1", request(months)))
        .isInstanceOf(ValidationException.class);
  }

  private static CreatePlanCheckoutRequest request(Integer months) {
    CreatePlanCheckoutRequest request = new CreatePlanCheckoutRequest();
    request.setPlanId("plan-1");
    request.setDurationMonths(months);
    return request;
  }
}
