package com.inventory.plan.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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
import com.inventory.plan.domain.model.ShopAddOn;
import com.inventory.plan.domain.model.VoucherRejection;
import com.inventory.plan.domain.model.VoucherType;
import com.inventory.plan.domain.repository.PlanRepository;
import com.inventory.plan.exception.VoucherRejectedException;
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
  @Mock private ShopAddOnService shopAddOnService;
  @Mock private PlanService planService;

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
  void planVoucherInAddOnOnlyCartRejected() {
    onPaidPlan();
    when(voucherService.requireUsable("PLAN-1K", "shop-1")).thenReturn(AddOnVoucher.builder().code("PLAN-1K")
        .target(com.inventory.plan.domain.model.VoucherTarget.PLAN).planCode("PROFESSIONAL")
        .type(VoucherType.FLAT_OFF).value(new BigDecimal("1000")).quantity(1).build());
    when(planRepository.findByCode("PROFESSIONAL")).thenReturn(Optional.of(pro));
    QuoteRequest request = addOnOnly(new QuoteRequest.AddOnLine("OCR_TOPUP_500", 1));
    request.setVoucherCodes(List.of("PLAN-1K"));

    assertThatThrownBy(() -> pricing.priceAddOnOnly("shop-1", request))
        .isInstanceOfSatisfying(VoucherRejectedException.class, e -> {
          assertThat(e.getReason()).isEqualTo(VoucherRejection.NOT_APPLICABLE_TO_CART);
          assertThat(e.getMessage()).isEqualTo("Voucher PLAN-1K is for Professional; add the plan to use it.");
        });
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
  void smsPacksAreComingSoonThroughAVoucherAsAVoucherRejection() {
    when(planRepository.findByCode("PROFESSIONAL")).thenReturn(Optional.of(pro));
    when(voucherService.requireUsable("SMS-FREE", "shop-1")).thenReturn(AddOnVoucher.builder().code("SMS-FREE")
        .addOnCode("SMS_PACK").type(VoucherType.FREE_ADDON).quantity(1).build());
    QuoteRequest request = new QuoteRequest();
    request.setPlanCode("PROFESSIONAL");
    request.setVoucherCodes(List.of("SMS-FREE"));

    assertThatThrownBy(() -> pricing.quote("shop-1", request))
        .hasMessage(PricingConstants.MSG_SMS_COMING_SOON)
        .isInstanceOfSatisfying(VoucherRejectedException.class,
            e -> assertThat(e.getReason()).isEqualTo(VoucherRejection.NOT_APPLICABLE_TO_CART));
  }

  @Test
  void quoteShowsWhenAnnualAddOnsEndAndPlanCartsDoNot() {
    onPaidPlan();
    assertThat(pricing.quote("shop-1", addOnOnly(new QuoteRequest.AddOnLine("MARKETING_MODULE", 1)))
        .getTermEndsAt()).isEqualTo(TERM_END);
    assertThat(pricing.priceAddOnOnly("shop-1", addOnOnly(new QuoteRequest.AddOnLine("OCR_TOPUP_500", 1)))
        .termEndsAt()).isEqualTo(TERM_END);

    when(planRepository.findByCode("PROFESSIONAL")).thenReturn(Optional.of(pro));
    QuoteRequest planCart = new QuoteRequest();
    planCart.setPlanCode("PROFESSIONAL");
    assertThat(pricing.quote("shop-1", planCart).getTermEndsAt()).isNull();
  }

  @Test
  void aLegacyPlanCannotBuyAnyAddOnBecauseItAlreadyHasEverything() {
    pro.setCode(null);
    onPaidPlan();

    for (String code : List.of("MARKETING_MODULE", "ADDITIONAL_USER", "OCR_TOPUP_500")) {
      assertThatThrownBy(() -> pricing.quote("shop-1", addOnOnly(new QuoteRequest.AddOnLine(code, 1))))
          .isInstanceOf(ValidationException.class).hasMessage(PricingConstants.MSG_LEGACY_PLAN_HAS_EVERYTHING);
    }
  }

  @Test
  void aNonStackableAddOnTheShopAlreadyHoldsCannotBeBoughtAgain() {
    onPaidPlan();
    when(shopAddOnService.live(eq("shop-1"), any())).thenReturn(List.of(
        held("MARKETING_MODULE", AddOnGrantType.FEATURE),
        held("ADDITIONAL_USER", AddOnGrantType.SEATS),
        held("OCR_TOPUP_500", AddOnGrantType.OCR_CREDITS)));

    assertThatThrownBy(() -> pricing.quote("shop-1", addOnOnly(new QuoteRequest.AddOnLine("MARKETING_MODULE", 1))))
        .isInstanceOf(ValidationException.class).hasMessageContaining("already have MARKETING_MODULE");

    // Seats and OCR credits stack, so a shop can always buy more.
    QuoteResponse more = pricing.quote("shop-1", addOnOnly(
        new QuoteRequest.AddOnLine("ADDITIONAL_USER", 1), new QuoteRequest.AddOnLine("OCR_TOPUP_500", 1)));
    assertThat(more.getGrandTotal()).isEqualByComparingTo("699");
  }

  @Test
  void aVoucherForAnAddOnTheShopAlreadyHoldsIsNotApplicable() {
    onPaidPlan();
    when(shopAddOnService.live(eq("shop-1"), any())).thenReturn(List.of(held("MARKETING_MODULE", AddOnGrantType.FEATURE)));
    when(voucherService.requireUsable("MKT-FREE", "shop-1")).thenReturn(AddOnVoucher.builder().code("MKT-FREE")
        .addOnCode("MARKETING_MODULE").type(VoucherType.FREE_ADDON).quantity(1).build());
    QuoteRequest request = new QuoteRequest();
    request.setVoucherCodes(List.of("MKT-FREE"));

    assertThatThrownBy(() -> pricing.quote("shop-1", request))
        .isInstanceOfSatisfying(VoucherRejectedException.class,
            e -> assertThat(e.getReason()).isEqualTo(VoucherRejection.NOT_APPLICABLE_TO_CART));
  }

  @Test
  void aPlanCartMayRebuyAnAddOnTheShopHolds() {
    when(planRepository.findByCode("PROFESSIONAL")).thenReturn(Optional.of(pro));
    QuoteRequest request = new QuoteRequest();
    request.setPlanCode("PROFESSIONAL");
    request.setAddOns(List.of(new QuoteRequest.AddOnLine("MARKETING_MODULE", 1)));

    assertThat(pricing.quote("shop-1", request).getItems()).hasSize(2);
    verify(shopAddOnService, never()).live(any(), any());
  }

  @Test
  void aVoucherOnlyCartWithNoPlanIsAnAddOnOnlyTopUp() {
    onPaidPlan();
    when(voucherService.requireUsable("MKT-FREE", "shop-1")).thenReturn(AddOnVoucher.builder().code("MKT-FREE")
        .addOnCode("MARKETING_MODULE").type(VoucherType.FREE_ADDON).quantity(1).build());
    QuoteRequest request = new QuoteRequest();
    request.setVoucherCodes(List.of("MKT-FREE"));

    QuoteResponse quote = pricing.quote("shop-1", request);

    assertThat(quote.isAddOnOnly()).isTrue();
    assertThat(quote.getItems()).singleElement()
        .satisfies(item -> assertThat(item.getItemSource()).isEqualTo("VOUCHER"));
    assertThat(quote.getGrandTotal()).isEqualByComparingTo("0");
    assertThat(quote.getTermEndsAt()).isEqualTo(TERM_END);
  }

  @Test
  void aVoucherOnlyCartStillNeedsAnActivePaidPlan() {
    when(entitlementService.paidTerm("shop-1")).thenReturn(Optional.empty());
    QuoteRequest request = new QuoteRequest();
    request.setVoucherCodes(List.of("MKT-FREE"));

    assertThatThrownBy(() -> pricing.quote("shop-1", request))
        .hasMessage(PricingConstants.MSG_ADD_ON_ONLY_NEEDS_PAID_PLAN);
  }

  private void catalogueListed() {
    when(addOnCatalogue.activeAddOns()).thenReturn(List.of(
        addOn("MARKETING_MODULE", "1999", AddOnGrantType.FEATURE, PlanFeature.MARKETING, false),
        addOn("ACCOUNTING", "999", AddOnGrantType.FEATURE, PlanFeature.ACCOUNTING, false),
        addOn("OCR_TOPUP_500", "199", AddOnGrantType.OCR_CREDITS, null, true),
        addOn("SMS_PACK", "700", AddOnGrantType.SMS, null, true)));
    when(addOnCatalogue.toOption(any(AddOn.class), any())).thenAnswer(inv -> {
      AddOn addOn = inv.getArgument(0);
      Optional<String> reason = inv.getArgument(1);
      com.inventory.plan.rest.dto.response.AddOnResponse r = new com.inventory.plan.rest.dto.response.AddOnResponse();
      r.setCode(addOn.getCode());
      r.setEligible(reason.isEmpty());
      r.setIneligibleReason(reason.orElse(null));
      return r;
    });
  }

  @Test
  void topUpOptionsUseTheCheckoutRules() {
    onPaidPlan();
    catalogueListed();
    when(shopAddOnService.live(eq("shop-1"), any())).thenReturn(List.of(held("MARKETING_MODULE", AddOnGrantType.FEATURE)));

    var options = pricing.addOnOptionsForShop("shop-1");

    assertThat(options).extracting(o -> o.getCode() + "=" + o.getEligible()).containsExactly(
        "MARKETING_MODULE=false", "ACCOUNTING=false", "OCR_TOPUP_500=true", "SMS_PACK=false");
    assertThat(options.get(0).getIneligibleReason()).isEqualTo("You already have MARKETING_MODULE");
    assertThat(options.get(1).getIneligibleReason()).isEqualTo("Professional already includes ACCOUNTING");
    assertThat(options.get(3).getIneligibleReason()).isEqualTo(PricingConstants.MSG_SMS_COMING_SOON);
  }

  @Test
  void withoutAPaidPlanNoAddOnCanBeBoughtAlone() {
    catalogueListed();
    when(entitlementService.paidTerm("shop-1")).thenReturn(Optional.empty());

    assertThat(pricing.addOnOptionsForShop("shop-1")).allSatisfy(o -> {
      assertThat(o.getEligible()).isFalse();
      assertThat(o.getIneligibleReason()).isEqualTo(PricingConstants.MSG_ADD_ON_ONLY_NEEDS_PAID_PLAN);
    });
  }

  @Test
  void planCartOptionsIgnoreWhatTheShopHolds() {
    catalogueListed();
    when(planRepository.findByCode("PROFESSIONAL")).thenReturn(Optional.of(pro));

    var options = pricing.addOnOptionsForPlan(" professional ");

    assertThat(options).extracting(o -> o.getCode() + "=" + o.getEligible()).containsExactly(
        "MARKETING_MODULE=true", "ACCOUNTING=false", "OCR_TOPUP_500=true", "SMS_PACK=false");
    verify(shopAddOnService, never()).live(any(), any());
  }

  private static ShopAddOn held(String code, AddOnGrantType type) {
    return ShopAddOn.builder().shopId("shop-1").addOnCode(code).grantType(type).expiresAt(TERM_END).build();
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
