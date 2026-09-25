package com.inventory.product.tax;

import java.math.BigDecimal;
import java.util.List;

/**
 * What a supplier invoice is worth for tax, line by line, and how confident that answer is.
 *
 * <p>The verdict matters as much as the figures. A taxable value derived from a stated header the
 * operator typed correctly is worth more than the same number recovered from line prices that may
 * or may not carry the bill's discount, and a return built from the second should say so rather
 * than presenting both with equal authority.
 */
public record PurchaseTaxBasis(List<Line> lines, Verdict verdict) {

  /** How the taxable value on a line was arrived at, in descending order of trust. */
  public enum Source {
    /** The stated header agrees with the line rates; the operator's figure is used as typed. */
    HEADER_CONSISTENT,
    /** Landed cost — quantity times cost after the recorded scheme and discount. */
    LINE_LANDED,
    /** The stated tax is trusted and the taxable value backed out of it. */
    DERIVED_FROM_TAX,
    /** A tax-inclusive amount with the tax extracted from it. */
    INCLUSIVE_EXTRACTED,
    /** Quantity times list cost, with no discount recorded anywhere. Gross, and probably high. */
    LINE_GROSS
  }

  /** What the invoice header says about itself once the lines are read back. */
  public enum Verdict {
    /** Header present and consistent with the lines. */
    OK,
    /** No usable header: the taxable value rests entirely on line prices. */
    MISSING,
    /** Header present but its own subtotal and tax do not agree at the line rates. */
    MISMATCH,
    /** The tax implied by the header is a GST slab that no line on the invoice carries. */
    RATE_CONFLICT
  }

  /**
   * One invoice line's tax.
   *
   * @param taxable value the tax is charged on
   * @param ratePct total GST rate (sgst + cgst, or the igst rate — they are the same number)
   * @param centralTax CGST, zero on an interstate supply
   * @param stateTax SGST, zero on an interstate supply
   * @param integratedTax IGST, zero on an intra-state supply
   * @param source how {@code taxable} was arrived at
   */
  public record Line(
      BigDecimal taxable,
      BigDecimal ratePct,
      BigDecimal centralTax,
      BigDecimal stateTax,
      BigDecimal integratedTax,
      Source source) {

    public BigDecimal tax() {
      return centralTax.add(stateTax).add(integratedTax);
    }
  }

  public BigDecimal totalTaxable() {
    return lines.stream().map(Line::taxable).reduce(BigDecimal.ZERO, BigDecimal::add);
  }

  public BigDecimal totalTax() {
    return lines.stream().map(Line::tax).reduce(BigDecimal.ZERO, BigDecimal::add);
  }
}
