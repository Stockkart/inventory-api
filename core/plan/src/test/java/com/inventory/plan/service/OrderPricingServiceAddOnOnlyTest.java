package com.inventory.plan.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.inventory.common.entitlement.PlanFeature;
import com.inventory.common.exception.ValidationException;
import com.inventory.plan.domain.model.AddOn;
import com.inventory.plan.domain.model.AddOnGrantType;
import com.inventory.plan.domain.model.AddOnVoucher;
import com.inventory.plan.domain.model.PaidTerm;
import com.inventory.plan.domain.model.Plan;
import com.inventory.plan.domain.model.PricedCart;
import com.inventory.plan.domain.model.VoucherType;
import com.inventory.plan.domain.repository.PlanRepository;
import com.inventory.plan.rest.dto.request.QuoteRequest;
import com.inventory.plan.rest.dto.response.QuoteResponse;
import com.inventory.plan.service.voucher.VoucherService;
import com.inventory.plan.service.wallet.WalletService;
import com.inventory.plan.utils.constants.PricingConstants;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Add-ons bought on their own against the shop's current paid plan, and SMS packs being unsold. */
@ExtendWith(MockitoExtension.class)
class OrderPricingServiceAddOnOnlyTest {

  private static final Instant TERM_END = Instant.parse("2027-03-01T00:00:00Z");

  @Mock private PlanRepository planRepository;
  @Mock private AddOnCatalogueService addOnCatalogue;
  @Mock private VoucherService voucherService;
  @Mock private WalletService walletService;
  @Mock private EntitlementService entitlementService;

  @InjectMocks
  private OrderPricingService pricing;

  private final Plan pro = plan();

  @BeforeEach
  void catalogue() {
    lenient().when(addOnCatalogue.byCode(anyCollection())).thenReturn(Map.of(
        "MARKETING_MODULE", addOn("MARKETING_MODULE", "1999", AddOnGrantType.FEATURE, PlanFeature.MARKETING, false),
        "ACCOUNTING", addOn("ACCOUNTING", "999", AddOnGrantType.FEATURE, PlanFeature.ACCOUNTING, false),
        "ADDITIONAL_USER", addOn("ADDITIONAL_USER", "500", AddOnGrantType.SEATS, null, true),
        "OCR_TOPUP_500", addOn("OCR_TOPUP_500", "199", AddOnGrantType.OCR_CREDITS, null, true),
        "SMS_PACK", addOn("SMS_PACK", "700", AddOnGrantType.SMS, null, true)));
  }

  @Test
  void pricesAddOnsAloneAgainstTheCurrentPaidPlan() {
    onPaidPlan();

    QuoteResponse quote = pricing.quote("shop-1", addOnOnly(
        new QuoteRequest.AddOnLine("marketing_module", null),
        new QuoteRequest.AddOnLine("ADDITIONAL_USER", 2),
        new QuoteRequest.AddOnLine("OCR_TOPUP_500", 1)));

    assertThat(quote.isAddOnOnly()).isTrue();
    assertThat(quote.getItems()).extracting(QuoteResponse.QuoteItem::getType)
        .containsExactly("ADDON", "ADDON", "OCR_TOPUP");
    assertThat(quote.getItems().get(1).getLineTotal()).isEqualByComparingTo("1000");
    assertThat(quote.getSubtotal()).isEqualByComparingTo("3198");
    assertThat(quote.getDiscountTotal()).isEqualByComparingTo("0");
    assertThat(quote.getWalletCredit()).isEqualByComparingTo("0");
    assertThat(quote.getGrandTotal()).isEqualByComparingTo("3198");
    assertThat(quote.getDurationMonths()).isZero();
    verify(planRepository, never()).findByCode(anyString());
  }

  @Test
  void durationIsIgnoredForAddOnOnlyCarts() {
    onPaidPlan();
    QuoteRequest request = addOnOnly(new QuoteRequest.AddOnLine("OCR_TOPUP_500", 1));
    request.setDurationMonths(24);

    assertThat(pricing.quote("shop-1", request).getGrandTotal()).isEqualByComparingTo("199");
  }

  @Test
  void planCartsAreNotAddOnOnly() {
    when(planRepository.findByCode("PROFESSIONAL")).thenReturn(Optional.of(pro));

    QuoteRequest request = new QuoteRequest();
    request.setPlanCode("PROFESSIONAL");
    assertThat(pricing.quote("shop-1", request).isAddOnOnly()).isFalse();
  }

  @Test
  void trialExpiredOrMissingPlanCannotBuyAddOnsAlone() {
    // paidTerm is empty on trial, after expiry and for undated subscriptions (EntitlementServiceTest).
    when(entitlementService.paidTerm("shop-1")).thenReturn(Optional.empty());

    assertThatThrownBy(() -> pricing.quote("shop-1", addOnOnly(new QuoteRequest.AddOnLine("OCR_TOPUP_500", 1))))
        .isInstanceOf(ValidationException.class)
        .hasMessage("Add-ons can be bought on their own only on an active paid plan");

    when(entitlementService.paidTerm("shop-1")).thenReturn(Optional.of(new PaidTerm("deleted-plan", TERM_END)));
    when(planRepository.findById("deleted-plan")).thenReturn(Optional.empty());
    assertThatThrownBy(() -> pricing.quote("shop-1", addOnOnly(new QuoteRequest.AddOnLine("OCR_TOPUP_500", 1))))
        .hasMessage(PricingConstants.MSG_ADD_ON_ONLY_NEEDS_PAID_PLAN);
  }

  @Test
  void anEmptyCartAsksForAPlanOrAnAddOn() {
    QuoteRequest empty = new QuoteRequest();
    empty.setAddOns(List.of());

    assertThatThrownBy(() -> pricing.quote("shop-1", empty))
        .isInstanceOf(ValidationException.class).hasMessage("Choose a plan or at least one add-on");
    assertThatThrownBy(() -> pricing.quote("shop-1", null)).hasMessage(PricingConstants.MSG_EMPTY_CART);
    assertThatThrownBy(() -> pricing.priceAddOnOnly("shop-1", empty)).hasMessage(PricingConstants.MSG_EMPTY_CART);
  }

  @Test
  void aFeatureThePlanAlreadyIncludesIsRejected() {
    onPaidPlan();

    assertThatThrownBy(() -> pricing.quote("shop-1", addOnOnly(new QuoteRequest.AddOnLine("ACCOUNTING", 1))))
        .isInstanceOf(ValidationException.class).hasMessageContaining("already includes");
  }

  @Test
  void seatsAreRejectedOnAnUnlimitedPlan() {
    pro.setUnlimited(true);
    onPaidPlan();

    assertThatThrownBy(() -> pricing.quote("shop-1", addOnOnly(new QuoteRequest.AddOnLine("ADDITIONAL_USER", 1))))
        .hasMessageContaining("unlimited users");
  }

  @Test
  void walletPaysAddOnsWithNoPlanFloorButNeverOcrTopUps() {
    pro.setBasePrice(new BigDecimal("9000"));
    onPaidPlan();
    when(walletService.available("shop-1")).thenReturn(new BigDecimal("5000"));
    QuoteRequest request = addOnOnly(
        new QuoteRequest.AddOnLine("OCR_TOPUP_500", 1),
        new QuoteRequest.AddOnLine("MARKETING_MODULE", 1));
    request.setApplyWalletCredit(true);

    QuoteResponse quote = pricing.quote("shop-1", request);

    assertThat(quote.getWalletCredit()).isEqualByComparingTo("1999");
    assertThat(quote.getItems().get(0).getWalletCredit()).isNull();
    assertThat(quote.getItems().get(1).getWalletCredit()).isEqualByComparingTo("1999");
    assertThat(quote.getGrandTotal()).isEqualByComparingTo("199");
  }

  @Test
  void vouchersStillApply() {
    onPaidPlan();
    when(voucherService.requireUsable("MKT-FREE", "shop-1")).thenReturn(AddOnVoucher.builder().code("MKT-FREE")
        .addOnCode("MARKETING_MODULE").type(VoucherType.FREE_ADDON).quantity(1).build());
    QuoteRequest request = addOnOnly(new QuoteRequest.AddOnLine("OCR_TOPUP_500", 1));
    request.setVoucherCodes(List.of("MKT-FREE"));

    PricedCart cart = pricing.priceAddOnOnly("shop-1", request);

    assertThat(cart.addOnOnly()).isTrue();
    assertThat(cart.plan()).isSameAs(pro);
    assertThat(cart.items()).hasSize(2);
    assertThat(cart.items().get(1).getItemSource()).isEqualTo("VOUCHER");
    assertThat(cart.discountTotal()).isEqualByComparingTo("1999");
    assertThat(cart.grandTotal()).isEqualByComparingTo("199");
  }

  @Test
  void smsPacksAreComingSoonInAnAddOnOnlyCart() {
    onPaidPlan();

    assertThatThrownBy(() -> pricing.quote("shop-1", addOnOnly(new QuoteRequest.AddOnLine("SMS_PACK", 1))))
        .isInstanceOf(ValidationException.class).hasMessage("SMS packs are coming soon");
  }

  @Test
  void smsPacksAreComingSoonInAPlanCart() {
    when(planRepository.findByCode("PROFESSIONAL")).thenReturn(Optional.of(pro));
    QuoteRequest request = new QuoteRequest();
    request.setPlanCode("PROFESSIONAL");
    request.setAddOns(List.of(new QuoteRequest.AddOnLine("SMS_PACK", 1)));

    assertThatThrownBy(() -> pricing.quote("shop-1", request))
        .isInstanceOf(ValidationException.class).hasMessage(PricingConstants.MSG_SMS_COMING_SOON);
  }

  @Test
  void smsPacksAreComingSoonThroughAVoucher() {
    when(planRepository.findByCode("PROFESSIONAL")).thenReturn(Optional.of(pro));
    when(voucherService.requireUsable("SMS-FREE", "shop-1")).thenReturn(AddOnVoucher.builder().code("SMS-FREE")
        .addOnCode("SMS_PACK").type(VoucherType.FREE_ADDON).quantity(1).build());
    QuoteRequest request = new QuoteRequest();
    request.setPlanCode("PROFESSIONAL");
    request.setVoucherCodes(List.of("SMS-FREE"));

    assertThatThrownBy(() -> pricing.quote("shop-1", request))
        .isExactlyInstanceOf(ValidationException.class).hasMessage(PricingConstants.MSG_SMS_COMING_SOON);
  }

  private void onPaidPlan() {
    when(entitlementService.paidTerm("shop-1")).thenReturn(Optional.of(new PaidTerm("plan-pro", TERM_END)));
    when(planRepository.findById("plan-pro")).thenReturn(Optional.of(pro));
  }

  private static QuoteRequest addOnOnly(QuoteRequest.AddOnLine... lines) {
    QuoteRequest request = new QuoteRequest();
    request.setAddOns(List.of(lines));
    return request;
  }

  private static AddOn addOn(String code, String price, AddOnGrantType type, PlanFeature feature, boolean stackable) {
    return AddOn.builder().code(code).name(code).price(new BigDecimal(price)).grantType(type)
        .grantsFeature(feature).stackable(stackable).active(true).build();
  }

  private static Plan plan() {
    Plan plan = new Plan();
    plan.setId("plan-pro");
    plan.setCode("PROFESSIONAL");
    plan.setPlanName("Professional");
    plan.setArcPrice(new BigDecimal("9999"));
    plan.setFeatures(Set.of(PlanFeature.ACCOUNTING));
    return plan;
  }
}
