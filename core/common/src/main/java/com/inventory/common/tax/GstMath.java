package com.inventory.common.tax;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Set;

/**
 * GST arithmetic shared by the purchase, sale and return paths.
 *
 * <p>Two conventions exist on Indian bills and the difference is not cosmetic. A supplier who
 * quotes a rate <em>ex-GST</em> states the taxable value and adds tax to it. A supplier who bills
 * at MRP states an amount that already contains the tax, and the taxable value has to be backed
 * out of it. Adding tax to an inclusive amount overstates both the value and the input credit, so
 * a caller must say which convention it is working in — there is no safe default here.
 *
 * <p>Money is carried at 2dp HALF_UP throughout, matching what the GST portal accepts.
 */
public final class GstMath {

  private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
  private static final BigDecimal TWO = BigDecimal.valueOf(2);

  /** Money scale used for every amount this class returns. */
  private static final int MONEY_SCALE = 2;

  /**
   * Rate scale for the intra-state half. A 5% slab halves exactly, but 0.25% does not, so the
   * half is carried at 4dp rather than rounded to the money scale before it is applied.
   */
  private static final int RATE_SCALE = 4;

  /** GST slabs a line may legitimately carry. Anything else is a data-entry error. */
  private static final Set<BigDecimal> KNOWN_SLABS = Set.of(
      new BigDecimal("0"),
      new BigDecimal("0.1"),
      new BigDecimal("0.25"),
      new BigDecimal("3"),
      new BigDecimal("5"),
      new BigDecimal("12"),
      new BigDecimal("18"),
      new BigDecimal("28"));

  private GstMath() {}

  /** Taxable value and the tax on it, however the two were arrived at. */
  public record TaxSplit(BigDecimal taxable, BigDecimal tax) {}

  /** The two halves of an intra-state tax. Equal by construction. */
  public record IntraStateTax(BigDecimal centralTax, BigDecimal stateTax) {
    public BigDecimal total() {
      return centralTax.add(stateTax);
    }
  }

  /**
   * Parses a stored rate such as {@code "9"}, {@code "9%"} or {@code " 2.5 "}.
   *
   * <p>Anything unparseable is zero rather than an exception: these strings come from user-entered
   * pricing records, and a report that omits one line's tax is easier to spot and fix than a
   * report that fails to generate at all. A negative rate is clamped to zero — it has no meaning
   * and would otherwise produce a negative credit.
   */
  public static BigDecimal parseRatePct(String raw) {
    if (raw == null) return BigDecimal.ZERO;
    String trimmed = raw.trim();
    if (trimmed.isEmpty()) return BigDecimal.ZERO;
    if (trimmed.endsWith("%")) {
      trimmed = trimmed.substring(0, trimmed.length() - 1).trim();
    }
    try {
      BigDecimal value = new BigDecimal(trimmed);
      return value.signum() < 0 ? BigDecimal.ZERO : value;
    } catch (NumberFormatException ex) {
      return BigDecimal.ZERO;
    }
  }

  /** True when the rate is a GST slab. Used to flag a line whose rate cannot be right. */
  public static boolean isKnownSlab(BigDecimal ratePct) {
    if (ratePct == null) return false;
    return KNOWN_SLABS.stream().anyMatch(slab -> slab.compareTo(ratePct) == 0);
  }

  /**
   * Tax added on top of a stated taxable value — the convention of a supplier who quotes ex-GST
   * rates and lists the tax separately.
   */
  public static BigDecimal taxOnExclusive(BigDecimal base, BigDecimal ratePct) {
    if (base == null || ratePct == null || ratePct.signum() <= 0) return BigDecimal.ZERO;
    return base.multiply(ratePct).divide(HUNDRED, MONEY_SCALE, RoundingMode.HALF_UP);
  }

  /**
   * Taxable value backed out of a tax-inclusive amount: {@code taxable = gross × 100 / (100 +
   * rate)}. This is the MRP-billed case, and the same identity GSTR-1 already applies to sale
   * lines.
   *
   * <p>The tax is returned as {@code gross − taxable} rather than recomputed from the rate, so the
   * two always add back to the amount actually on the bill.
   */
  public static TaxSplit extractFromInclusive(BigDecimal gross, BigDecimal ratePct) {
    if (gross == null) return new TaxSplit(BigDecimal.ZERO, BigDecimal.ZERO);
    BigDecimal amount = gross.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    if (ratePct == null || ratePct.signum() <= 0) {
      return new TaxSplit(amount, BigDecimal.ZERO);
    }
    BigDecimal taxable = amount.multiply(HUNDRED)
        .divide(HUNDRED.add(ratePct), MONEY_SCALE, RoundingMode.HALF_UP);
    return new TaxSplit(taxable, amount.subtract(taxable));
  }

  /**
   * Splits an intra-state tax into its central and state halves.
   *
   * <p>Each half is computed from half the rate rather than by halving the total. Halving would
   * hand the odd paisa to one side; an intra-state purchase is taxed at half the rate twice, and
   * the two halves are equal.
   */
  public static IntraStateTax splitIntraState(BigDecimal taxable, BigDecimal ratePct) {
    if (taxable == null || ratePct == null || ratePct.signum() <= 0) {
      return new IntraStateTax(BigDecimal.ZERO, BigDecimal.ZERO);
    }
    BigDecimal half = ratePct.divide(TWO, RATE_SCALE, RoundingMode.HALF_UP);
    BigDecimal each = taxable.multiply(half).divide(HUNDRED, MONEY_SCALE, RoundingMode.HALF_UP);
    return new IntraStateTax(each, each);
  }
}
