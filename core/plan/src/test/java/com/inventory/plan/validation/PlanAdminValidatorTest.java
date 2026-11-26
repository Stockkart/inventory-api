package com.inventory.plan.validation;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.inventory.common.exception.ValidationException;
import com.inventory.plan.rest.dto.request.PlanActiveRequest;
import com.inventory.plan.rest.dto.request.PlanAdminRequest;
import java.math.BigDecimal;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PlanAdminValidatorTest {

  private final PlanAdminValidator validator = new PlanAdminValidator();

  private static PlanAdminRequest valid() {
    PlanAdminRequest request = new PlanAdminRequest();
    request.setCode("GROWTH");
    request.setPlanName("Growth");
    request.setArcPrice(new BigDecimal("7999"));
    return request;
  }

  private void rejects(Consumer<PlanAdminRequest> change) {
    PlanAdminRequest request = valid();
    change.accept(request);
    assertThatThrownBy(() -> validator.validateCreate(request)).isInstanceOf(ValidationException.class);
  }

  @Test
  void acceptsMinimalPlan() {
    assertThatCode(() -> validator.validateCreate(valid())).doesNotThrowAnyException();
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "ab", "growth", "1GROWTH", "GROW-TH", "A_VERY_LONG_PLAN_CODE_THAT_OVERFLOWS"})
  void rejectsBadCodes(String code) {
    rejects(r -> r.setCode(code));
  }

  @Test
  void rejectsMissingNameOrPrice() {
    rejects(r -> r.setPlanName(" "));
    rejects(r -> r.setArcPrice(null));
  }

  @Test
  void rejectsNegativeNumbers() {
    rejects(r -> r.setArcPrice(new BigDecimal("-1")));
    rejects(r -> r.setPrice(new BigDecimal("-1")));
    rejects(r -> r.setOcrLimit(-1));
    rejects(r -> r.setSmsLimit(-1));
    rejects(r -> r.setUserLimit(0));
    rejects(r -> r.setRoleLimit(0));
    rejects(r -> r.setAttendanceUserLimit(0));
    rejects(r -> r.setDisplayOrder(-1));
  }

  @Test
  void acceptsBasePriceUpToTheAnnualPrice() {
    PlanAdminRequest request = valid();
    request.setBasePrice(new BigDecimal("7999"));
    assertThatCode(() -> validator.validateCreate(request)).doesNotThrowAnyException();
    request.setBasePrice(new BigDecimal("4999"));
    assertThatCode(() -> validator.validateCreate(request)).doesNotThrowAnyException();
  }

  @Test
  void rejectsBasePriceAboveTheAnnualPriceOrNegative() {
    rejects(r -> r.setBasePrice(new BigDecimal("8000")));
    rejects(r -> r.setBasePrice(new BigDecimal("-1")));
  }

  @Test
  void rejectsBadBadge() {
    rejects(r -> r.setBadge("most popular"));
  }

  @Test
  void updateRejectsSelfUpsell() {
    PlanAdminRequest request = valid();
    request.setLinkedId("p1");
    assertThatThrownBy(() -> validator.validateUpdate("p1", request)).isInstanceOf(ValidationException.class);
  }

  @Test
  void activeRequiresFlag() {
    assertThatThrownBy(() -> validator.validateActive("p1", new PlanActiveRequest(null, "x")))
        .isInstanceOf(ValidationException.class);
  }

  @Test
  void aPlanGrantReplacesAnotherPlanOnlyWhenNoPaidDaysAreLeft() {
    PlanAdminValidator v = new PlanAdminValidator();
    java.time.Instant now = java.time.Instant.parse("2026-11-21T06:00:00Z");
    org.junit.jupiter.api.Assertions.assertThrows(ValidationException.class,
        () -> v.validateGrantTarget("starter", now.plusSeconds(60), "pro", false, now));
    org.junit.jupiter.api.Assertions.assertDoesNotThrow(
        () -> v.validateGrantTarget("pro", now.plusSeconds(60), "pro", false, now));
    org.junit.jupiter.api.Assertions.assertDoesNotThrow(
        () -> v.validateGrantTarget("starter", now.minusSeconds(60), "pro", false, now));
    org.junit.jupiter.api.Assertions.assertDoesNotThrow(
        () -> v.validateGrantTarget(null, now.plusSeconds(60), "pro", true, now));
  }
}
