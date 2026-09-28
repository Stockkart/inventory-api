package com.inventory.plan.service.mis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.inventory.common.exception.ValidationException;
import com.inventory.plan.domain.model.AddOn;
import com.inventory.plan.domain.model.OrderLine;
import com.inventory.plan.domain.model.PlanPaymentOrder;
import com.inventory.plan.domain.model.ReferralReward;
import com.inventory.plan.domain.model.ReferralRewardStatus;
import com.inventory.plan.domain.model.ShopCredit;
import com.inventory.plan.domain.model.ShopCreditEntry;
import com.inventory.plan.domain.model.ShopCreditSource;
import com.inventory.plan.domain.model.VoucherRedemption;
import com.inventory.plan.domain.repository.AddOnRepository;
import com.inventory.plan.domain.repository.PlanPaymentOrderRepository;
import com.inventory.plan.domain.repository.ReferralRewardRepository;
import com.inventory.plan.domain.repository.ShopCreditEntryRepository;
import com.inventory.plan.domain.repository.ShopCreditRepository;
import com.inventory.plan.domain.repository.VoucherRedemptionRepository;
import com.inventory.plan.rest.dto.response.PlanMisResponse;
import com.inventory.plan.utils.constants.PlanPaymentConstants;
import com.inventory.plan.utils.constants.PricingConstants;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PlanMisServiceTest {

  /** 1 Sep 2026 00:00 in Asia/Kolkata. */
  private static final Instant SEP_1_IST = Instant.parse("2026-08-31T18:30:00Z");

  @Mock private PlanPaymentOrderRepository orderRepository;
  @Mock private ReferralRewardRepository rewardRepository;
  @Mock private VoucherRedemptionRepository redemptionRepository;
  @Mock private ShopCreditEntryRepository creditEntryRepository;
  @Mock private ShopCreditRepository creditRepository;
  @Mock private AddOnRepository addOnRepository;

  @InjectMocks
  private PlanMisService service;

  @BeforeEach
  void setUp() {
    service.clock = Clock.fixed(Instant.parse("2026-09-30T12:00:00Z"), ZoneOffset.UTC);
    lenient().when(orderRepository.findByStatusInAndPaidAtGreaterThanEqualAndPaidAtLessThan(any(), any(), any()))
        .thenReturn(List.of());
  }

  private static BigDecimal money(String value) {
    return new BigDecimal(value);
  }

  private static OrderLine line(String type, String code, int quantity, String total) {
    return OrderLine.builder().type(type).code(code).name(code + " name").quantity(quantity).lineTotal(money(total)).build();
  }

  private static PlanPaymentOrder order(String id, String shopId, String amount, Instant paidAt, OrderLine... lines) {
    PlanPaymentOrder order = new PlanPaymentOrder();
    order.setId(id);
    order.setShopId(shopId);
    order.setAmount(money(amount));
    order.setPaidAt(paidAt);
    order.setStatus(PlanPaymentConstants.STATUS_FULFILLED);
    order.setItems(List.of(lines));
    return order;
  }

  @Test
  void rangeIsInclusiveCalendarDaysInIndia() {
    service.report(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30));

    verify(orderRepository).findByStatusInAndPaidAtGreaterThanEqualAndPaidAtLessThan(
        PlanMisService.SOLD_STATUSES, SEP_1_IST, Instant.parse("2026-09-30T18:30:00Z"));
  }

  @Test
  void defaultsToTheLastThirtyDays() {
    PlanMisResponse report = service.report(null, null);

    assertThat(report.getTo()).isEqualTo(LocalDate.of(2026, 9, 30));
    assertThat(report.getFrom()).isEqualTo(LocalDate.of(2026, 9, 1));
  }

  @Test
  void rejectsBackwardsAndOverlongRanges() {
    assertThatThrownBy(() -> service.report(LocalDate.of(2026, 9, 2), LocalDate.of(2026, 9, 1)))
        .isInstanceOf(ValidationException.class);
    assertThatThrownBy(() -> service.report(LocalDate.of(2025, 1, 1), LocalDate.of(2026, 9, 1)))
        .isInstanceOf(ValidationException.class);
  }

  @Test
  void revenueCountsRefundsWhenTheyHappen() {
    PlanPaymentOrder paid = order("o1", "s1", "10000", SEP_1_IST, line(PricingConstants.ITEM_TYPE_PLAN, "PRO", 1, "10000"));
    paid.setDiscountTotal(money("500"));
    paid.setWalletCredit(money("200"));
    PlanPaymentOrder gatewayRefund = order("o2", "s2", "8000", SEP_1_IST);
    gatewayRefund.setRefundedAmount(money("8000"));
    PlanPaymentOrder offlineRefund = order("o3", "s3", "3000", SEP_1_IST);

    PlanMisResponse.Revenue revenue = PlanMisService.revenue(List.of(paid), List.of(gatewayRefund, offlineRefund));

    assertThat(revenue.getGrossRevenue()).isEqualByComparingTo("10000");
    assertThat(revenue.getDiscounts()).isEqualByComparingTo("500");
    assertThat(revenue.getWalletCreditApplied()).isEqualByComparingTo("200");
    assertThat(revenue.getRefundedAmount()).isEqualByComparingTo("11000");
    assertThat(revenue.getNetRevenue()).isEqualByComparingTo("-1000");
  }

  @Test
  void planSalesGroupByTierLargestFirst() {
    List<PlanMisResponse.PlanSales> sales = PlanMisService.planSales(List.of(
        order("o1", "s1", "5000", SEP_1_IST, line(PricingConstants.ITEM_TYPE_PLAN, "STARTER", 1, "5000")),
        order("o2", "s2", "12000", SEP_1_IST, line(PricingConstants.ITEM_TYPE_PLAN, "PRO", 1, "12000")),
        order("o3", "s3", "5000", SEP_1_IST, line(PricingConstants.ITEM_TYPE_PLAN, "STARTER", 1, "5000"))));

    assertThat(sales).extracting(PlanMisResponse.PlanSales::getPlanCode).containsExactly("PRO", "STARTER");
    assertThat(sales.get(1).getOrders()).isEqualTo(2);
    assertThat(sales.get(1).getRevenue()).isEqualByComparingTo("10000");
  }

  @Test
  void legacyOrdersWithoutLinesCountAsOnePlanLine() {
    PlanPaymentOrder legacy = order("o1", "s1", "4000", SEP_1_IST);
    legacy.setItems(null);
    legacy.setPlanCode("SILVER");

    assertThat(PlanMisService.planSales(List.of(legacy)))
        .singleElement()
        .satisfies(row -> assertThat(row.getRevenue()).isEqualByComparingTo("4000"));
  }

  @Test
  void attachRateIsPlanOrdersThatAlsoBoughtAnExtra() {
    PlanMisResponse.AddOns addOns = PlanMisService.addOns(List.of(
        order("o1", "s1", "0", SEP_1_IST, line(PricingConstants.ITEM_TYPE_PLAN, "PRO", 1, "10000"),
            line(PricingConstants.ITEM_TYPE_ADDON, "MARKETING", 1, "2000")),
        order("o2", "s2", "0", SEP_1_IST, line(PricingConstants.ITEM_TYPE_PLAN, "PRO", 1, "10000")),
        order("o3", "s3", "0", SEP_1_IST, line(PricingConstants.ITEM_TYPE_PLAN, "PRO", 1, "10000"),
            line(PricingConstants.ITEM_TYPE_OCR_TOPUP, "OCR_500", 2, "1000")),
        order("o4", "s4", "0", SEP_1_IST, line(PricingConstants.ITEM_TYPE_ADDON, "MARKETING", 1, "2000"))));

    assertThat(addOns.getPlanOrders()).isEqualTo(3);
    assertThat(addOns.getPlanOrdersWithAddOn()).isEqualTo(2);
    assertThat(addOns.getAttachRatePercent()).isEqualByComparingTo("66.67");
    assertThat(addOns.getItems()).extracting(PlanMisResponse.AddOnSales::getCode).containsExactly("MARKETING", "OCR_500");
    assertThat(addOns.getItems().get(0).getOrders()).isEqualTo(2);
  }

  @Test
  void ocrCreditsUseTheCatalogueQuantity() {
    when(addOnRepository.findByCodeIn(anyCollection()))
        .thenReturn(List.of(AddOn.builder().code("OCR_500").grantsQuantity(500).build()));

    PlanMisResponse.OcrTopUps topUps = service.ocrTopUps(List.of(
        order("o1", "s1", "0", SEP_1_IST, line(PricingConstants.ITEM_TYPE_OCR_TOPUP, "OCR_500", 3, "1500"))));

    assertThat(topUps.getUnits()).isEqualTo(3);
    assertThat(topUps.getCredits()).isEqualTo(1500);
    assertThat(topUps.getRevenue()).isEqualByComparingTo("1500");
  }

  @Test
  void voucherCostRanksCodesByDiscount() {
    PlanMisResponse.Vouchers vouchers = PlanMisService.vouchers(List.of(
        VoucherRedemption.builder().voucherCode("A").discount(money("100")).build(),
        VoucherRedemption.builder().voucherCode("B").discount(money("500")).build(),
        VoucherRedemption.builder().voucherCode("A").discount(money("100")).build()));

    assertThat(vouchers.getDiscountValue()).isEqualByComparingTo("700");
    assertThat(vouchers.getTopCodes()).extracting(PlanMisResponse.VoucherCost::getVoucherCode).containsExactly("B", "A");
    assertThat(vouchers.getTopCodes().get(1).getRedemptions()).isEqualTo(2);
  }

  @Test
  void referralCostIsMeasuredAgainstFirstTimeRevenue() {
    Instant later = SEP_1_IST.plusSeconds(86_400);
    PlanPaymentOrder newShop = order("o1", "new", "10000", SEP_1_IST);
    PlanPaymentOrder newShopSecond = order("o2", "new", "5000", later);
    PlanPaymentOrder returning = order("o3", "old", "20000", SEP_1_IST);
    when(orderRepository.findByStatusInAndPaidAtGreaterThanEqualAndPaidAtLessThan(any(), any(), any()))
        .thenReturn(List.of(newShopSecond, newShop, returning));
    PlanPaymentOrder earlier = new PlanPaymentOrder();
    earlier.setShopId("old");
    when(orderRepository.findPaidBefore(anyCollection(), eq(PlanMisService.SOLD_STATUSES), eq(SEP_1_IST)))
        .thenReturn(List.of(earlier));
    when(rewardRepository.findByCreatedAtGreaterThanEqualAndCreatedAtLessThan(any(), any())).thenReturn(List.of(
        ReferralReward.builder().rewardAmount(money("1000")).status(ReferralRewardStatus.PENDING).build(),
        ReferralReward.builder().rewardAmount(money("800")).status(ReferralRewardStatus.VOID).build(),
        ReferralReward.builder().rewardAmount(money("500")).status(ReferralRewardStatus.CLAWED_BACK).build()));

    PlanMisResponse.Referrals referrals = service.report(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30)).getReferrals();

    assertThat(referrals.getNewShops()).isEqualTo(1);
    assertThat(referrals.getNewRevenue()).isEqualByComparingTo("10000");
    assertThat(referrals.getRewardsEarned()).isEqualTo(1);
    assertThat(referrals.getRewardCost()).isEqualByComparingTo("1000");
    assertThat(referrals.getRewardsVoided()).isEqualTo(1);
    assertThat(referrals.getCostPercentOfNewRevenue()).isEqualByComparingTo("10.00");
  }

  @Test
  void walletTotalsBySourceWithSignedManualAdjustments() {
    List<ShopCreditEntry> entries = List.of(
        ShopCreditEntry.builder().source(ShopCreditSource.REFERRAL_REWARD).amount(money("1000")).build(),
        ShopCreditEntry.builder().source(ShopCreditSource.ORDER_REDEMPTION).amount(money("400")).build(),
        ShopCreditEntry.builder().source(ShopCreditSource.MANUAL_ADJUSTMENT).amount(money("100"))
            .availableDelta(money("70")).outstandingDelta(money("-30")).build(),
        ShopCreditEntry.builder().source(ShopCreditSource.MANUAL_ADJUSTMENT).amount(money("50"))
            .availableDelta(money("-50")).outstandingDelta(BigDecimal.ZERO).build());
    List<ShopCredit> wallets = List.of(
        ShopCredit.builder().availableBalance(money("600")).reservedBalance(money("100"))
            .outstandingClawback(money("25")).build());

    PlanMisResponse.Wallet wallet = PlanMisService.wallet(entries, wallets);

    assertThat(wallet.getRewardsCredited()).isEqualByComparingTo("1000");
    assertThat(wallet.getSpentOnOrders()).isEqualByComparingTo("400");
    assertThat(wallet.getManualAdjustmentsNet()).isEqualByComparingTo("50");
    assertThat(wallet.getOutstandingLiability()).isEqualByComparingTo("700");
    assertThat(wallet.getUnrecoveredClawback()).isEqualByComparingTo("25");
  }

  @Test
  void percentIsNullWithoutADenominator() {
    assertThat(PlanMisService.percent(BigDecimal.ONE, BigDecimal.ZERO)).isNull();
  }
}
