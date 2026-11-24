package com.inventory.plan.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.inventory.common.exception.ResourceNotFoundException;
import com.inventory.common.exception.ValidationException;
import com.inventory.plan.domain.model.Plan;
import com.inventory.plan.domain.repository.PlanRepository;
import com.inventory.plan.rest.dto.request.QuoteRequest;
import com.inventory.plan.rest.dto.response.QuoteResponse;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class OrderPricingServiceTest {

  @Mock
  private PlanRepository planRepository;

  @InjectMocks
  private OrderPricingService pricing;

  @Test
  void pricesPlanAtItsYearlyPrice() {
    when(planRepository.findByCode("PROFESSIONAL")).thenReturn(Optional.of(plan("PROFESSIONAL", "9999", null)));

    QuoteResponse quote = pricing.quote(request("PROFESSIONAL"));

    assertThat(quote.getItems()).singleElement().satisfies(item -> {
      assertThat(item.getType()).isEqualTo("PLAN");
      assertThat(item.getCode()).isEqualTo("PROFESSIONAL");
      assertThat(item.getName()).isEqualTo("Professional");
      assertThat(item.getQuantity()).isEqualTo(1);
      assertThat(item.getUnitPrice()).isEqualByComparingTo("9999");
      assertThat(item.getLineTotal()).isEqualByComparingTo("9999");
      assertThat(item.getItemSource()).isEqualTo("MANUAL");
    });
    assertThat(quote.getSubtotal()).isEqualByComparingTo("9999");
    assertThat(quote.getDiscountTotal()).isEqualByComparingTo("0");
    assertThat(quote.getWalletCredit()).isEqualByComparingTo("0");
    assertThat(quote.getGrandTotal()).isEqualByComparingTo("9999");
    assertThat(quote.isTaxInclusive()).isTrue();
    assertThat(quote.getCurrency()).isEqualTo("INR");
    assertThat(quote.getDurationMonths()).isEqualTo(12);
    assertThat(quote.getPricingVersion()).isEqualTo(1);
    assertThat(Duration.between(quote.getQuotedAt(), quote.getExpiresAt())).isEqualTo(Duration.ofMinutes(15));
  }

  @Test
  void normalisesPlanCode() {
    when(planRepository.findByCode("STARTER")).thenReturn(Optional.of(plan("STARTER", "6999", null)));

    assertThat(pricing.quote(request("  starter ")).getGrandTotal()).isEqualByComparingTo("6999");
  }

  @Test
  void quoteAndCheckoutUseTheSamePrice() {
    Plan legacy = plan(null, null, "4999");

    assertThat(pricing.planPrice(legacy)).isEqualByComparingTo("4999");
    assertThat(pricing.planPrice(plan("X", "9999", "0"))).isEqualByComparingTo("9999");
  }

  @Test
  void unknownPlanIsNotFound() {
    when(planRepository.findByCode("GOLD")).thenReturn(Optional.empty());

    assertThatThrownBy(() -> pricing.quote(request("GOLD"))).isInstanceOf(ResourceNotFoundException.class);
  }

  @Test
  void deactivatedPlanIsNotFound() {
    Plan retired = plan("STARTER", "6999", null);
    retired.setActive(false);
    when(planRepository.findByCode("STARTER")).thenReturn(Optional.of(retired));

    assertThatThrownBy(() -> pricing.quote(request("STARTER"))).isInstanceOf(ResourceNotFoundException.class);
  }

  @Test
  void unpricedPlanIsNotForSale() {
    when(planRepository.findByCode("FREE")).thenReturn(Optional.of(plan("FREE", null, "0")));

    assertThatThrownBy(() -> pricing.quote(request("FREE"))).isInstanceOf(ValidationException.class);
  }

  @Test
  void rejectsMissingPlanCodeAndNonYearlyDuration() {
    assertThatThrownBy(() -> pricing.quote(request(" "))).isInstanceOf(ValidationException.class);
    assertThatThrownBy(() -> pricing.quote(null)).isInstanceOf(ValidationException.class);

    QuoteRequest twoYears = request("PROFESSIONAL");
    twoYears.setDurationMonths(24);
    assertThatThrownBy(() -> pricing.quote(twoYears)).isInstanceOf(ValidationException.class);
    verify(planRepository, never()).findByCode(anyString());
  }

  @Test
  void rejectsAddOnsAndVouchersUntilTheyExist() {
    QuoteRequest withAddOn = request("PROFESSIONAL");
    withAddOn.setAddOns(List.of(new QuoteRequest.AddOnLine("MARKETING_MODULE", 1)));
    assertThatThrownBy(() -> pricing.quote(withAddOn)).isInstanceOf(ValidationException.class);

    QuoteRequest withVoucher = request("PROFESSIONAL");
    withVoucher.setVoucherCodes(List.of("MKT-9F3K2P"));
    assertThatThrownBy(() -> pricing.quote(withVoucher)).isInstanceOf(ValidationException.class);
  }

  @Test
  void walletFlagIsAcceptedAndAppliesNothingYet() {
    when(planRepository.findByCode("PROFESSIONAL")).thenReturn(Optional.of(plan("PROFESSIONAL", "9999", null)));
    QuoteRequest request = request("PROFESSIONAL");
    request.setApplyWalletCredit(true);

    QuoteResponse quote = pricing.quote(request);

    assertThat(quote.getWalletCredit()).isEqualByComparingTo("0");
    assertThat(quote.getGrandTotal()).isEqualByComparingTo("9999");
  }

  @Test
  void quotingOnlyReads() {
    when(planRepository.findByCode("PROFESSIONAL")).thenReturn(Optional.of(plan("PROFESSIONAL", "9999", null)));

    pricing.quote(request("PROFESSIONAL"));

    verify(planRepository).findByCode("PROFESSIONAL");
    verifyNoMoreInteractions(planRepository);
  }

  private static QuoteRequest request(String planCode) {
    QuoteRequest request = new QuoteRequest();
    request.setPlanCode(planCode);
    return request;
  }

  private static Plan plan(String code, String arcPrice, String price) {
    Plan plan = new Plan();
    plan.setCode(code);
    plan.setPlanName(code == null ? "Legacy" : code.charAt(0) + code.substring(1).toLowerCase());
    plan.setArcPrice(arcPrice == null ? null : new BigDecimal(arcPrice));
    plan.setPrice(price == null ? null : new BigDecimal(price));
    return plan;
  }
}
