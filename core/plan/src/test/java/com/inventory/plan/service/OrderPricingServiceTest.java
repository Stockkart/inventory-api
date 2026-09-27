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
import com.inventory.common.entitlement.PlanFeature;
import com.inventory.plan.domain.model.AddOn;
import com.inventory.plan.domain.model.AddOnGrantType;
import com.inventory.plan.domain.model.AddOnVoucher;
import com.inventory.plan.domain.model.Plan;
import com.inventory.plan.domain.model.VoucherRejection;
import com.inventory.plan.domain.model.VoucherType;
import com.inventory.plan.domain.repository.PlanRepository;
import com.inventory.plan.exception.VoucherRejectedException;
import com.inventory.plan.rest.dto.request.QuoteRequest;
import com.inventory.plan.rest.dto.response.QuoteResponse;
import com.inventory.plan.service.voucher.VoucherService;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class OrderPricingServiceTest {

  @Mock
  private PlanRepository planRepository;

  @Mock
  private AddOnCatalogueService addOnCatalogue;

  @Mock
  private VoucherService voucherService;

  @InjectMocks
  private OrderPricingService pricing;

  @Test
  void pricesPlanAtItsYearlyPrice() {
    when(planRepository.findByCode("PROFESSIONAL")).thenReturn(Optional.of(plan("PROFESSIONAL", "9999", null)));

    QuoteResponse quote = pricing.quote("shop-1", request("PROFESSIONAL"));

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

    assertThat(pricing.quote("shop-1", request("  starter ")).getGrandTotal()).isEqualByComparingTo("6999");
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

    assertThatThrownBy(() -> pricing.quote("shop-1", request("GOLD"))).isInstanceOf(ResourceNotFoundException.class);
  }

  @Test
  void deactivatedPlanIsNotFound() {
    Plan retired = plan("STARTER", "6999", null);
    retired.setActive(false);
    when(planRepository.findByCode("STARTER")).thenReturn(Optional.of(retired));

    assertThatThrownBy(() -> pricing.quote("shop-1", request("STARTER"))).isInstanceOf(ResourceNotFoundException.class);
  }

  @Test
  void unpricedPlanIsNotForSale() {
    when(planRepository.findByCode("FREE")).thenReturn(Optional.of(plan("FREE", null, "0")));

    assertThatThrownBy(() -> pricing.quote("shop-1", request("FREE"))).isInstanceOf(ValidationException.class);
  }

  @Test
  void rejectsMissingPlanCodeAndNonYearlyDuration() {
    assertThatThrownBy(() -> pricing.quote("shop-1", request(" "))).isInstanceOf(ValidationException.class);
    assertThatThrownBy(() -> pricing.quote("shop-1", null)).isInstanceOf(ValidationException.class);

    QuoteRequest twoYears = request("PROFESSIONAL");
    twoYears.setDurationMonths(24);
    assertThatThrownBy(() -> pricing.quote("shop-1", twoYears)).isInstanceOf(ValidationException.class);
    verify(planRepository, never()).findByCode(anyString());
  }

  @Test
  void pricesAddOnLinesFromTheCatalogue() {
    when(planRepository.findByCode("PROFESSIONAL")).thenReturn(Optional.of(plan("PROFESSIONAL", "9999", null)));
    when(addOnCatalogue.byCode(Set.of("MARKETING_MODULE", "ADDITIONAL_USER", "OCR_TOPUP_500"))).thenReturn(Map.of(
        "MARKETING_MODULE", addOn("MARKETING_MODULE", "1999", AddOnGrantType.FEATURE, PlanFeature.MARKETING, false, null),
        "ADDITIONAL_USER", addOn("ADDITIONAL_USER", "500", AddOnGrantType.SEATS, null, true, 50),
        "OCR_TOPUP_500", addOn("OCR_TOPUP_500", "199", AddOnGrantType.OCR_CREDITS, null, true, 20)));
    QuoteRequest request = request("PROFESSIONAL");
    request.setAddOns(List.of(
        new QuoteRequest.AddOnLine("marketing_module", null),
        new QuoteRequest.AddOnLine("ADDITIONAL_USER", 2),
        new QuoteRequest.AddOnLine("OCR_TOPUP_500", 1)));

    QuoteResponse quote = pricing.quote("shop-1", request);

    assertThat(quote.getItems()).extracting(QuoteResponse.QuoteItem::getType)
        .containsExactly("PLAN", "ADDON", "ADDON", "OCR_TOPUP");
    assertThat(quote.getItems().get(2).getLineTotal()).isEqualByComparingTo("1000");
    assertThat(quote.getSubtotal()).isEqualByComparingTo("13197");
    assertThat(quote.getGrandTotal()).isEqualByComparingTo("13197");
  }

  @Test
  void rejectsAddOnsThatDoNotApply() {
    Plan pro = plan("PROFESSIONAL", "9999", null);
    pro.setFeatures(Set.of(PlanFeature.ACCOUNTING));
    when(planRepository.findByCode("PROFESSIONAL")).thenReturn(Optional.of(pro));
    AddOn accounting = addOn("ACCOUNTING", "999", AddOnGrantType.FEATURE, PlanFeature.ACCOUNTING, false, null);
    AddOn marketing = addOn("MARKETING_MODULE", "1999", AddOnGrantType.FEATURE, PlanFeature.MARKETING, false, null);
    AddOn hidden = addOn("SALARY_MODULE", "1499", AddOnGrantType.FEATURE, PlanFeature.SALARY, false, null);
    hidden.setActive(false);
    AddOn seats = addOn("ADDITIONAL_USER", "500", AddOnGrantType.SEATS, null, true, 5);
    when(addOnCatalogue.byCode(org.mockito.ArgumentMatchers.anyCollection())).thenReturn(Map.of(
        "ACCOUNTING", accounting, "MARKETING_MODULE", marketing, "SALARY_MODULE", hidden, "ADDITIONAL_USER", seats));

    assertThatThrownBy(() -> pricing.quote("shop-1", withAddOn("ACCOUNTING", 1)))
        .isInstanceOf(ValidationException.class).hasMessageContaining("already includes");
    assertThatThrownBy(() -> pricing.quote("shop-1", withAddOn("MARKETING_MODULE", 2)))
        .isInstanceOf(ValidationException.class).hasMessageContaining("once per order");
    assertThatThrownBy(() -> pricing.quote("shop-1", withAddOn("ADDITIONAL_USER", 6)))
        .isInstanceOf(ValidationException.class).hasMessageContaining("At most 5");
    assertThatThrownBy(() -> pricing.quote("shop-1", withAddOn("ADDITIONAL_USER", 0)))
        .isInstanceOf(ValidationException.class);
    assertThatThrownBy(() -> pricing.quote("shop-1", withAddOn("SALARY_MODULE", 1)))
        .isInstanceOf(ResourceNotFoundException.class);
    assertThatThrownBy(() -> pricing.quote("shop-1", withAddOn("NOPE", 1)))
        .isInstanceOf(ResourceNotFoundException.class);

    QuoteRequest repeated = request("PROFESSIONAL");
    repeated.setAddOns(List.of(new QuoteRequest.AddOnLine("ADDITIONAL_USER", 1), new QuoteRequest.AddOnLine("additional_user", 1)));
    assertThatThrownBy(() -> pricing.quote("shop-1", repeated)).hasMessageContaining("more than once");
  }

  @Test
  void seatAddOnIsRejectedForUnlimitedPlans() {
    Plan enterprise = plan("ENTERPRISE", "19999", null);
    enterprise.setUnlimited(true);
    when(planRepository.findByCode("ENTERPRISE")).thenReturn(Optional.of(enterprise));
    when(addOnCatalogue.byCode(Set.of("ADDITIONAL_USER"))).thenReturn(Map.of(
        "ADDITIONAL_USER", addOn("ADDITIONAL_USER", "500", AddOnGrantType.SEATS, null, true, 50)));
    QuoteRequest request = request("ENTERPRISE");
    request.setAddOns(List.of(new QuoteRequest.AddOnLine("ADDITIONAL_USER", 1)));

    assertThatThrownBy(() -> pricing.quote("shop-1", request)).hasMessageContaining("unlimited users");
  }

  @Test
  void freeVoucherForAnAddOnNotInTheCartAddsAZeroLine() {
    proWithMarketingAddOn();
    when(voucherService.requireUsable("MKT-9F3K2P", "shop-1")).thenReturn(voucher("MKT-9F3K2P", VoucherType.FREE_ADDON, null));
    QuoteRequest request = request("PROFESSIONAL");
    request.setVoucherCodes(List.of("mkt-9f3k2p"));

    QuoteResponse quote = pricing.quote("shop-1", request);

    assertThat(quote.getItems()).hasSize(2);
    QuoteResponse.QuoteItem line = quote.getItems().get(1);
    assertThat(line.getItemSource()).isEqualTo("VOUCHER");
    assertThat(line.getVoucherCode()).isEqualTo("MKT-9F3K2P");
    assertThat(line.getDiscount()).isEqualByComparingTo("1999");
    assertThat(line.getLineTotal()).isEqualByComparingTo("0");
    assertThat(quote.getSubtotal()).isEqualByComparingTo("11998");
    assertThat(quote.getDiscountTotal()).isEqualByComparingTo("1999");
    assertThat(quote.getGrandTotal()).isEqualByComparingTo("9999");
  }

  @Test
  void freeVoucherForASelectedAddOnZeroesThatLineAndKeepsItManual() {
    proWithMarketingAddOn();
    when(voucherService.requireUsable("MKT-9F3K2P", "shop-1")).thenReturn(voucher("MKT-9F3K2P", VoucherType.FREE_ADDON, null));
    QuoteRequest request = withAddOn("MARKETING_MODULE", 1);
    request.setVoucherCodes(List.of("MKT-9F3K2P"));

    QuoteResponse quote = pricing.quote("shop-1", request);

    assertThat(quote.getItems()).hasSize(2);
    assertThat(quote.getItems().get(1).getItemSource()).isEqualTo("MANUAL");
    assertThat(quote.getItems().get(1).getLineTotal()).isEqualByComparingTo("0");
    assertThat(quote.getGrandTotal()).isEqualByComparingTo("9999");
  }

  @Test
  void percentAndFlatVouchersDiscountTheLine() {
    proWithMarketingAddOn();
    when(voucherService.requireUsable("MKT-HALF", "shop-1"))
        .thenReturn(voucher("MKT-HALF", VoucherType.PERCENT_OFF, new BigDecimal("50")));
    QuoteRequest request = request("PROFESSIONAL");
    request.setVoucherCodes(List.of("MKT-HALF"));

    assertThat(pricing.quote("shop-1", request).getGrandTotal()).isEqualByComparingTo("10998.50");

    when(voucherService.requireUsable("MKT-FLAT", "shop-1"))
        .thenReturn(voucher("MKT-FLAT", VoucherType.FLAT_OFF, new BigDecimal("5000")));
    request.setVoucherCodes(List.of("MKT-FLAT"));
    assertThat(pricing.quote("shop-1", request).getGrandTotal()).isEqualByComparingTo("9999");
  }

  @Test
  void voucherRejectionsAreTyped() {
    Plan pro = plan("PROFESSIONAL", "9999", null);
    pro.setFeatures(Set.of(PlanFeature.MARKETING));
    when(planRepository.findByCode("PROFESSIONAL")).thenReturn(Optional.of(pro));
    when(addOnCatalogue.byCode(List.of("MARKETING_MODULE"))).thenReturn(Map.of("MARKETING_MODULE",
        addOn("MARKETING_MODULE", "1999", AddOnGrantType.FEATURE, PlanFeature.MARKETING, false, null)));
    when(voucherService.requireUsable("MKT-9F3K2P", "shop-1")).thenReturn(voucher("MKT-9F3K2P", VoucherType.FREE_ADDON, null));
    QuoteRequest request = request("PROFESSIONAL");
    request.setVoucherCodes(List.of("MKT-9F3K2P"));

    assertThatThrownBy(() -> pricing.quote("shop-1", request))
        .isInstanceOfSatisfying(VoucherRejectedException.class,
            e -> assertThat(e.getReason()).isEqualTo(VoucherRejection.NOT_APPLICABLE_TO_CART));
  }

  @Test
  void oneVoucherPerLine() {
    proWithMarketingAddOn();
    when(voucherService.requireUsable("MKT-A", "shop-1")).thenReturn(voucher("MKT-A", VoucherType.FREE_ADDON, null));
    when(voucherService.requireUsable("MKT-B", "shop-1")).thenReturn(voucher("MKT-B", VoucherType.FREE_ADDON, null));
    QuoteRequest request = request("PROFESSIONAL");
    request.setVoucherCodes(List.of("MKT-A", "MKT-B"));

    assertThatThrownBy(() -> pricing.quote("shop-1", request))
        .isInstanceOfSatisfying(VoucherRejectedException.class,
            e -> assertThat(e.getReason()).isEqualTo(VoucherRejection.NOT_APPLICABLE_TO_CART));
  }

  @Test
  void vouchersNeedAShopAndDistinctCodes() {
    when(planRepository.findByCode("PROFESSIONAL")).thenReturn(Optional.of(plan("PROFESSIONAL", "9999", null)));
    QuoteRequest request = request("PROFESSIONAL");
    request.setVoucherCodes(List.of("MKT-A"));
    assertThatThrownBy(() -> pricing.quote(null, request)).isInstanceOf(ValidationException.class);

    request.setVoucherCodes(List.of("MKT-A", "mkt-a"));
    assertThatThrownBy(() -> pricing.quote("shop-1", request)).hasMessageContaining("more than once");
  }

  private void proWithMarketingAddOn() {
    when(planRepository.findByCode("PROFESSIONAL")).thenReturn(Optional.of(plan("PROFESSIONAL", "9999", null)));
    AddOn marketing = addOn("MARKETING_MODULE", "1999", AddOnGrantType.FEATURE, PlanFeature.MARKETING, false, null);
    org.mockito.Mockito.lenient().when(addOnCatalogue.byCode(List.of("MARKETING_MODULE")))
        .thenReturn(Map.of("MARKETING_MODULE", marketing));
    org.mockito.Mockito.lenient().when(addOnCatalogue.byCode(Set.of("MARKETING_MODULE")))
        .thenReturn(Map.of("MARKETING_MODULE", marketing));
  }

  private static AddOnVoucher voucher(String code, VoucherType type, BigDecimal value) {
    return AddOnVoucher.builder().code(code).addOnCode("MARKETING_MODULE").type(type).value(value).quantity(1).build();
  }

  private static QuoteRequest withAddOn(String code, int quantity) {
    QuoteRequest request = request("PROFESSIONAL");
    request.setAddOns(List.of(new QuoteRequest.AddOnLine(code, quantity)));
    return request;
  }

  private static AddOn addOn(String code, String price, AddOnGrantType type, PlanFeature feature,
      boolean stackable, Integer maxQuantity) {
    return AddOn.builder().code(code).name(code).price(new BigDecimal(price)).grantType(type)
        .grantsFeature(feature).stackable(stackable).maxQuantity(maxQuantity).active(true).build();
  }

  @Test
  void walletFlagIsAcceptedAndAppliesNothingYet() {
    when(planRepository.findByCode("PROFESSIONAL")).thenReturn(Optional.of(plan("PROFESSIONAL", "9999", null)));
    QuoteRequest request = request("PROFESSIONAL");
    request.setApplyWalletCredit(true);

    QuoteResponse quote = pricing.quote("shop-1", request);

    assertThat(quote.getWalletCredit()).isEqualByComparingTo("0");
    assertThat(quote.getGrandTotal()).isEqualByComparingTo("9999");
  }

  @Test
  void quotingOnlyReads() {
    when(planRepository.findByCode("PROFESSIONAL")).thenReturn(Optional.of(plan("PROFESSIONAL", "9999", null)));

    pricing.quote("shop-1", request("PROFESSIONAL"));

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
