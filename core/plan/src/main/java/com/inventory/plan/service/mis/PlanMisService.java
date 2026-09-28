package com.inventory.plan.service.mis;

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
import com.inventory.plan.domain.model.VoucherRedemptionStatus;
import com.inventory.plan.domain.repository.AddOnRepository;
import com.inventory.plan.domain.repository.PlanPaymentOrderRepository;
import com.inventory.plan.domain.repository.ReferralRewardRepository;
import com.inventory.plan.domain.repository.ShopCreditEntryRepository;
import com.inventory.plan.domain.repository.ShopCreditRepository;
import com.inventory.plan.domain.repository.VoucherRedemptionRepository;
import com.inventory.plan.rest.dto.response.PlanMisResponse;
import com.inventory.plan.utils.constants.CampaignConstants;
import com.inventory.plan.utils.constants.PlanPaymentConstants;
import com.inventory.plan.utils.constants.PricingConstants;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Revenue MIS (5e). Plan orders are few enough (one or two per shop a year) that each report reads the
 * range's documents and totals them here, which keeps attach rate and first-order logic readable.
 */
@Service
public class PlanMisService {

  static final int DEFAULT_DAYS = 30;
  static final int MAX_DAYS = 366;
  static final int TOP_VOUCHERS = 20;
  /** Paid, whatever happened after, including a later refund. */
  static final List<String> SOLD_STATUSES = Stream.concat(PlanPaymentConstants.PAID_STATUSES.stream(),
      Stream.of(PlanPaymentConstants.STATUS_REFUNDED)).toList();
  private static final Set<ReferralRewardStatus> NOT_A_COST =
      Set.of(ReferralRewardStatus.VOID, ReferralRewardStatus.CLAWED_BACK);

  @Autowired
  private PlanPaymentOrderRepository orderRepository;

  @Autowired
  private ReferralRewardRepository rewardRepository;

  @Autowired
  private VoucherRedemptionRepository redemptionRepository;

  @Autowired
  private ShopCreditEntryRepository creditEntryRepository;

  @Autowired
  private ShopCreditRepository creditRepository;

  @Autowired
  private AddOnRepository addOnRepository;

  Clock clock = Clock.systemUTC();

  /** Both days inclusive, in Asia/Kolkata. Defaults to the last {@value #DEFAULT_DAYS} days. */
  public PlanMisResponse report(LocalDate from, LocalDate to) {
    LocalDate end = to != null ? to : LocalDate.now(clock.withZone(CampaignConstants.CAMPAIGN_ZONE));
    LocalDate start = from != null ? from : end.minusDays(DEFAULT_DAYS - 1L);
    if (start.isAfter(end)) {
      throw new ValidationException("'from' must not be after 'to'");
    }
    if (ChronoUnit.DAYS.between(start, end) >= MAX_DAYS) {
      throw new ValidationException("Reports cover at most " + MAX_DAYS + " days");
    }
    Instant rangeStart = start.atStartOfDay(CampaignConstants.CAMPAIGN_ZONE).toInstant();
    Instant rangeEnd = end.plusDays(1).atStartOfDay(CampaignConstants.CAMPAIGN_ZONE).toInstant();

    List<PlanPaymentOrder> sold =
        orderRepository.findByStatusInAndPaidAtGreaterThanEqualAndPaidAtLessThan(SOLD_STATUSES, rangeStart, rangeEnd);
    List<PlanPaymentOrder> refunded =
        orderRepository.findByRefundedAtGreaterThanEqualAndRefundedAtLessThan(rangeStart, rangeEnd);
    List<PlanPaymentOrder> firstOrders = firstOrders(sold, rangeStart);

    return PlanMisResponse.builder()
        .from(start)
        .to(end)
        .currency(PlanPaymentConstants.CURRENCY_INR)
        .revenue(revenue(sold, refunded))
        .planSales(planSales(sold))
        .addOns(addOns(sold))
        .ocrTopUps(ocrTopUps(sold))
        .vouchers(vouchers(redemptionRepository.findByStatusAndRedeemedAtGreaterThanEqualAndRedeemedAtLessThan(
            VoucherRedemptionStatus.REDEEMED, rangeStart, rangeEnd)))
        .referrals(referrals(rangeStart, rangeEnd, firstOrders))
        .wallet(wallet(creditEntryRepository.findByCreatedAtGreaterThanEqualAndCreatedAtLessThan(rangeStart, rangeEnd),
            creditRepository.findAll()))
        .build();
  }

  static PlanMisResponse.Revenue revenue(List<PlanPaymentOrder> sold, List<PlanPaymentOrder> refunded) {
    BigDecimal gross = sum(sold, PlanPaymentOrder::getAmount);
    BigDecimal refundedAmount = sum(refunded, PlanMisService::refundValue);
    return PlanMisResponse.Revenue.builder()
        .paidOrders(sold.size())
        .grossRevenue(gross)
        .discounts(sum(sold, PlanPaymentOrder::getDiscountTotal))
        .walletCreditApplied(sum(sold, PlanPaymentOrder::getWalletCredit))
        .refundedOrders(refunded.size())
        .refundedAmount(refundedAmount)
        .netRevenue(gross.subtract(refundedAmount))
        .build();
  }

  /** An admin refund made outside the gateway records no refundedAmount; a full refund is the whole charge. */
  static BigDecimal refundValue(PlanPaymentOrder order) {
    if (order.getRefundedAmount() != null && order.getRefundedAmount().signum() > 0) {
      return order.getRefundedAmount();
    }
    return order.getAmount();
  }

  static List<PlanMisResponse.PlanSales> planSales(List<PlanPaymentOrder> sold) {
    Map<String, PlanMisResponse.PlanSales> byCode = new LinkedHashMap<>();
    for (PlanPaymentOrder order : sold) {
      for (OrderLine line : lines(order, PricingConstants.ITEM_TYPE_PLAN)) {
        String code = line.getCode() != null ? line.getCode() : order.getPlanCode();
        PlanMisResponse.PlanSales row = byCode.computeIfAbsent(String.valueOf(code), c -> PlanMisResponse.PlanSales.builder()
            .planCode(code).planName(line.getName() != null ? line.getName() : order.getPlanName())
            .revenue(BigDecimal.ZERO).build());
        row.setOrders(row.getOrders() + 1);
        row.setRevenue(row.getRevenue().add(orZero(line.getLineTotal())));
      }
    }
    return byCode.values().stream()
        .sorted(Comparator.comparing(PlanMisResponse.PlanSales::getRevenue).reversed())
        .toList();
  }

  static PlanMisResponse.AddOns addOns(List<PlanPaymentOrder> sold) {
    long planOrders = 0;
    long withAddOn = 0;
    Map<String, PlanMisResponse.AddOnSales> byCode = new LinkedHashMap<>();
    for (PlanPaymentOrder order : sold) {
      List<OrderLine> extras = new ArrayList<>(lines(order, PricingConstants.ITEM_TYPE_ADDON));
      extras.addAll(lines(order, PricingConstants.ITEM_TYPE_OCR_TOPUP));
      if (!lines(order, PricingConstants.ITEM_TYPE_PLAN).isEmpty()) {
        planOrders++;
        if (!extras.isEmpty()) {
          withAddOn++;
        }
      }
      Set<String> counted = new HashSet<>();
      for (OrderLine line : extras) {
        PlanMisResponse.AddOnSales row = byCode.computeIfAbsent(line.getCode(), c -> PlanMisResponse.AddOnSales.builder()
            .code(c).name(line.getName()).revenue(BigDecimal.ZERO).build());
        if (counted.add(line.getCode())) {
          row.setOrders(row.getOrders() + 1);
        }
        row.setUnits(row.getUnits() + line.getQuantity());
        row.setRevenue(row.getRevenue().add(orZero(line.getLineTotal())));
      }
    }
    return PlanMisResponse.AddOns.builder()
        .planOrders(planOrders)
        .planOrdersWithAddOn(withAddOn)
        .attachRatePercent(percent(BigDecimal.valueOf(withAddOn), BigDecimal.valueOf(planOrders)))
        .items(byCode.values().stream()
            .sorted(Comparator.comparing(PlanMisResponse.AddOnSales::getRevenue).reversed())
            .toList())
        .build();
  }

  PlanMisResponse.OcrTopUps ocrTopUps(List<PlanPaymentOrder> sold) {
    List<OrderLine> topUps = sold.stream()
        .flatMap(order -> lines(order, PricingConstants.ITEM_TYPE_OCR_TOPUP).stream())
        .toList();
    Map<String, Integer> creditsPerUnit = topUps.isEmpty() ? Map.of()
        : addOnRepository.findByCodeIn(topUps.stream().map(OrderLine::getCode).collect(Collectors.toSet())).stream()
            .filter(a -> a.getGrantsQuantity() != null)
            .collect(Collectors.toMap(AddOn::getCode, AddOn::getGrantsQuantity, (a, b) -> a));
    return PlanMisResponse.OcrTopUps.builder()
        .orders(sold.stream().filter(o -> !lines(o, PricingConstants.ITEM_TYPE_OCR_TOPUP).isEmpty()).count())
        .units(topUps.stream().mapToLong(OrderLine::getQuantity).sum())
        .credits(topUps.stream().mapToLong(l -> (long) l.getQuantity() * creditsPerUnit.getOrDefault(l.getCode(), 0)).sum())
        .revenue(sum(topUps, OrderLine::getLineTotal))
        .build();
  }

  static PlanMisResponse.Vouchers vouchers(List<VoucherRedemption> redeemed) {
    Map<String, PlanMisResponse.VoucherCost> byCode = new HashMap<>();
    for (VoucherRedemption redemption : redeemed) {
      PlanMisResponse.VoucherCost row = byCode.computeIfAbsent(redemption.getVoucherCode(),
          c -> PlanMisResponse.VoucherCost.builder().voucherCode(c).discountValue(BigDecimal.ZERO).build());
      row.setRedemptions(row.getRedemptions() + 1);
      row.setDiscountValue(row.getDiscountValue().add(orZero(redemption.getDiscount())));
    }
    return PlanMisResponse.Vouchers.builder()
        .redemptions(redeemed.size())
        .discountValue(sum(redeemed, VoucherRedemption::getDiscount))
        .topCodes(byCode.values().stream()
            .sorted(Comparator.comparing(PlanMisResponse.VoucherCost::getDiscountValue).reversed()
                .thenComparing(PlanMisResponse.VoucherCost::getVoucherCode))
            .limit(TOP_VOUCHERS)
            .toList())
        .build();
  }

  private PlanMisResponse.Referrals referrals(Instant from, Instant to, List<PlanPaymentOrder> firstOrders) {
    List<ReferralReward> earned = rewardRepository.findByCreatedAtGreaterThanEqualAndCreatedAtLessThan(from, to);
    List<ReferralReward> costs = earned.stream().filter(r -> !NOT_A_COST.contains(r.getStatus())).toList();
    BigDecimal rewardCost = sum(costs, ReferralReward::getRewardAmount);
    BigDecimal newRevenue = sum(firstOrders, PlanPaymentOrder::getAmount);
    return PlanMisResponse.Referrals.builder()
        .rewardsEarned(costs.size())
        .rewardCost(rewardCost)
        .rewardsVoided(earned.stream().filter(r -> r.getStatus() == ReferralRewardStatus.VOID).count())
        .creditedAmount(sum(rewardRepository.findByCreditedAtGreaterThanEqualAndCreditedAtLessThan(from, to),
            ReferralReward::getRewardAmount))
        .clawedBackAmount(sum(rewardRepository.findByClawedBackAtGreaterThanEqualAndClawedBackAtLessThan(from, to),
            ReferralReward::getRewardAmount))
        .newShops(firstOrders.size())
        .newRevenue(newRevenue)
        .costPercentOfNewRevenue(percent(rewardCost, newRevenue))
        .build();
  }

  /** Each shop's earliest order in the range, for shops that had never paid before it. */
  private List<PlanPaymentOrder> firstOrders(List<PlanPaymentOrder> sold, Instant rangeStart) {
    Map<String, PlanPaymentOrder> earliest = new HashMap<>();
    for (PlanPaymentOrder order : sold) {
      earliest.merge(order.getShopId(), order,
          (a, b) -> a.getPaidAt().isAfter(b.getPaidAt()) ? b : a);
    }
    if (earliest.isEmpty()) {
      return List.of();
    }
    Set<String> returning = orderRepository.findPaidBefore(earliest.keySet(), SOLD_STATUSES, rangeStart).stream()
        .map(PlanPaymentOrder::getShopId)
        .collect(Collectors.toSet());
    return earliest.entrySet().stream()
        .filter(e -> !returning.contains(e.getKey()))
        .map(Map.Entry::getValue)
        .toList();
  }

  static PlanMisResponse.Wallet wallet(List<ShopCreditEntry> entries, List<ShopCredit> wallets) {
    Map<ShopCreditSource, List<ShopCreditEntry>> bySource = entries.stream()
        .filter(e -> e.getSource() != null)
        .collect(Collectors.groupingBy(ShopCreditEntry::getSource));
    Function<ShopCreditSource, BigDecimal> total =
        source -> sum(bySource.getOrDefault(source, List.of()), ShopCreditEntry::getAmount);
    BigDecimal manualNet = bySource.getOrDefault(ShopCreditSource.MANUAL_ADJUSTMENT, List.of()).stream()
        .map(e -> orZero(e.getAvailableDelta()).subtract(orZero(e.getOutstandingDelta())))
        .reduce(BigDecimal.ZERO, BigDecimal::add);
    return PlanMisResponse.Wallet.builder()
        .rewardsCredited(total.apply(ShopCreditSource.REFERRAL_REWARD))
        .spentOnOrders(total.apply(ShopCreditSource.ORDER_REDEMPTION))
        .refundedToWallet(total.apply(ShopCreditSource.ORDER_REFUND))
        .clawedBack(total.apply(ShopCreditSource.CLAWBACK))
        .manualAdjustmentsNet(manualNet)
        .outstandingLiability(sum(wallets, w -> orZero(w.getAvailableBalance()).add(orZero(w.getReservedBalance()))))
        .unrecoveredClawback(sum(wallets, ShopCredit::getOutstandingClawback))
        .build();
  }

  private static List<OrderLine> lines(PlanPaymentOrder order, String type) {
    if (order.getItems() == null) {
      return PricingConstants.ITEM_TYPE_PLAN.equals(type) && order.getPlanCode() != null
          ? List.of(OrderLine.builder().type(type).code(order.getPlanCode()).name(order.getPlanName())
              .quantity(1).lineTotal(order.getAmount()).build())
          : List.of();
    }
    return order.getItems().stream().filter(l -> type.equals(l.getType())).toList();
  }

  /** Two decimals; null when the denominator is zero. */
  static BigDecimal percent(BigDecimal part, BigDecimal whole) {
    if (whole == null || whole.signum() == 0) {
      return null;
    }
    return part.multiply(BigDecimal.valueOf(100)).divide(whole, 2, RoundingMode.HALF_UP);
  }

  private static <T> BigDecimal sum(Collection<T> items, Function<T, BigDecimal> value) {
    return items.stream().map(value).filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
  }

  private static BigDecimal orZero(BigDecimal value) {
    return value == null ? BigDecimal.ZERO : value;
  }
}
