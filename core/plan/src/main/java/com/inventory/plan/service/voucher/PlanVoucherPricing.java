package com.inventory.plan.service.voucher;

import com.inventory.plan.domain.model.AddOnVoucher;
import com.inventory.plan.domain.model.OrderLine;
import com.inventory.plan.domain.model.Plan;
import com.inventory.plan.domain.model.VoucherType;
import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Single source of truth for plan-voucher discount, base-price floor and maxima. Creation, the admin
 * preview and checkout all call this so they never disagree. Every helper except {@link #planPrice}
 * assumes the plan has a positive price; callers check that first.
 */
public final class PlanVoucherPricing {

  private static final BigDecimal MIN_FLOOR = new BigDecimal("1.00");
  private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

  private PlanVoucherPricing() {
  }

  /** Yearly price of a plan: the ARC price, else the list price. May be null. */
  public static BigDecimal planPrice(Plan plan) {
    return plan.getArcPrice() != null ? plan.getArcPrice() : plan.getPrice();
  }

  /** Lowest price a plan voucher may take the plan to: the base price, or Rs 1 when there is none. */
  public static BigDecimal floor(Plan plan) {
    return plan.getBasePrice() != null ? plan.getBasePrice().setScale(2, RoundingMode.UP) : MIN_FLOOR;
  }

  /** Most a plan voucher may take off: price minus floor, never below 0. Floor rounds up and room down, so extra decimals can never let a voucher cross the floor. */
  public static BigDecimal room(Plan plan) {
    return planPrice(plan).subtract(floor(plan)).max(BigDecimal.ZERO).setScale(2, RoundingMode.DOWN);
  }

  /** Highest percent off that stays on the floor, rounded down so it can never cross it. */
  public static BigDecimal maxPercent(Plan plan) {
    return room(plan).multiply(HUNDRED).divide(planPrice(plan), 2, RoundingMode.DOWN);
  }

  /** The voucher's discount on a one-unit plan line, before the floor is applied. */
  public static BigDecimal rawDiscount(VoucherType type, BigDecimal value, Plan plan) {
    BigDecimal price = planPrice(plan);
    OrderLine line = OrderLine.builder().quantity(1).unitPrice(price).build();
    AddOnVoucher voucher = AddOnVoucher.builder().type(type).value(value).build();
    return money(VoucherService.discountFor(voucher, line));
  }

  /** The discount checkout gives: the raw discount, held to the room above the floor. */
  public static BigDecimal cappedDiscount(VoucherType type, BigDecimal value, Plan plan) {
    return money(rawDiscount(type, value, plan).min(room(plan)).max(BigDecimal.ZERO));
  }

  /** Checks a voucher against the plan: the discount, the price after it, the maxima, and whether it stays on the floor. */
  public static PlanVoucherCheck check(VoucherType type, BigDecimal value, Plan plan) {
    BigDecimal price = money(planPrice(plan));
    BigDecimal raw = rawDiscount(type, value, plan);
    BigDecimal room = room(plan);
    return new PlanVoucherCheck(
        price,
        raw,
        money(price.subtract(raw)),
        plan.getBasePrice() == null ? null : money(plan.getBasePrice()),
        floor(plan),
        room,
        maxPercent(plan),
        raw.compareTo(room) <= 0);
  }

  /** Creation error text for a voucher that takes the plan below its floor (spec 7.2). */
  public static String belowFloorMessage(Plan plan, PlanVoucherCheck check) {
    String below = check.basePrice() == null
        ? "the " + rupees(MIN_FLOOR) + " minimum"
        : "its base price " + rupees(check.basePrice());
    return rupees(check.discount()) + " off takes " + plan.getPlanName() + " to " + rupees(check.priceAfter())
        + ", below " + below + ". Most you can give: " + rupees(check.maxFlat())
        + " (" + check.maxPercent().toPlainString() + "%).";
  }

  private static BigDecimal money(BigDecimal value) {
    return value.setScale(2, RoundingMode.HALF_UP);
  }

  /** Rupees with Indian digit grouping (1,00,000); paise shown only when not whole. */
  private static String rupees(BigDecimal amount) {
    BigDecimal value = money(amount);
    boolean whole = value.stripTrailingZeros().scale() <= 0;
    String plain = value.abs().setScale(whole ? 0 : 2, RoundingMode.HALF_UP).toPlainString();
    int dot = plain.indexOf('.');
    String integer = dot < 0 ? plain : plain.substring(0, dot);
    String fraction = dot < 0 ? "" : plain.substring(dot);
    StringBuilder grouped = new StringBuilder();
    int len = integer.length();
    if (len <= 3) {
      grouped.append(integer);
    } else {
      String head = integer.substring(0, len - 3);
      for (int i = 0; i < head.length(); i++) {
        if (i > 0 && (head.length() - i) % 2 == 0) {
          grouped.append(',');
        }
        grouped.append(head.charAt(i));
      }
      grouped.append(',').append(integer, len - 3, len);
    }
    return (value.signum() < 0 ? "-₹" : "₹") + grouped + fraction;
  }
}
