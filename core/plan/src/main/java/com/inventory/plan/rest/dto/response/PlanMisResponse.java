package com.inventory.plan.rest.dto.response;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Revenue MIS for plans, add-ons, vouchers, referrals and the wallet over a date range (5e). */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PlanMisResponse {

  /** Inclusive calendar days in Asia/Kolkata. */
  private LocalDate from;
  private LocalDate to;
  private String currency;
  private Revenue revenue;
  private List<PlanSales> planSales;
  private AddOns addOns;
  private OcrTopUps ocrTopUps;
  private Vouchers vouchers;
  private Referrals referrals;
  private Wallet wallet;

  /** Orders paid in the range, including ones refunded later; refunds count on the day they happened. */
  @Data
  @Builder
  @NoArgsConstructor
  @AllArgsConstructor
  public static class Revenue {
    private long paidOrders;
    /** Charged through the gateway: the orders' grand totals. */
    private BigDecimal grossRevenue;
    private BigDecimal discounts;
    private BigDecimal walletCreditApplied;
    private long refundedOrders;
    private BigDecimal refundedAmount;
    private BigDecimal netRevenue;
  }

  @Data
  @Builder
  @NoArgsConstructor
  @AllArgsConstructor
  public static class PlanSales {
    private String planCode;
    private String planName;
    private long orders;
    /** Plan line totals after discounts, before wallet credit. */
    private BigDecimal revenue;
  }

  @Data
  @Builder
  @NoArgsConstructor
  @AllArgsConstructor
  public static class AddOns {
    private long planOrders;
    private long planOrdersWithAddOn;
    /** Share of plan orders that also bought an add-on or OCR top-up, 0–100. */
    private BigDecimal attachRatePercent;
    private List<AddOnSales> items;
  }

  @Data
  @Builder
  @NoArgsConstructor
  @AllArgsConstructor
  public static class AddOnSales {
    private String code;
    private String name;
    private long orders;
    private long units;
    private BigDecimal revenue;
  }

  @Data
  @Builder
  @NoArgsConstructor
  @AllArgsConstructor
  public static class OcrTopUps {
    private long orders;
    private long units;
    /** Units × credits per unit from the catalogue. */
    private long credits;
    private BigDecimal revenue;
  }

  @Data
  @Builder
  @NoArgsConstructor
  @AllArgsConstructor
  public static class Vouchers {
    private long redemptions;
    private BigDecimal discountValue;
    /** Largest discount first, at most {@code TOP_VOUCHERS}. */
    private List<VoucherCost> topCodes;
  }

  @Data
  @Builder
  @NoArgsConstructor
  @AllArgsConstructor
  public static class VoucherCost {
    private String voucherCode;
    private long redemptions;
    private BigDecimal discountValue;
  }

  @Data
  @Builder
  @NoArgsConstructor
  @AllArgsConstructor
  public static class Referrals {
    /** Rewards earned in the range and still owed or paid (not voided or clawed back). */
    private long rewardsEarned;
    private BigDecimal rewardCost;
    private long rewardsVoided;
    private BigDecimal creditedAmount;
    private BigDecimal clawedBackAmount;
    /** Shops whose first paid order fell in the range, and what those orders brought in. */
    private long newShops;
    private BigDecimal newRevenue;
    /** rewardCost ÷ newRevenue × 100; null when there was no new revenue. */
    private BigDecimal costPercentOfNewRevenue;
  }

  @Data
  @Builder
  @NoArgsConstructor
  @AllArgsConstructor
  public static class Wallet {
    private BigDecimal rewardsCredited;
    private BigDecimal spentOnOrders;
    private BigDecimal refundedToWallet;
    private BigDecimal clawedBack;
    /** Credits minus debits. */
    private BigDecimal manualAdjustmentsNet;
    /** Current balances across all wallets, not limited to the range. */
    private BigDecimal outstandingLiability;
    private BigDecimal unrecoveredClawback;
  }
}
