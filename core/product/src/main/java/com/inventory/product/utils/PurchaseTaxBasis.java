package com.inventory.product.utils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * What a supplier invoice is worth for tax, line by line.
 *
 * @param lines each line's taxable value and tax, in invoice order
 * @param overallDiscount the bill-level discount taken off the lines before tax, zero when none was
 *     applied
 */
public record PurchaseTaxBasis(List<Line> lines, BigDecimal overallDiscount) {

  /**
   * One invoice line's tax.
   *
   * @param taxable value the tax is charged on
   * @param ratePct total GST rate (sgst + cgst, or the igst rate — they are the same number)
   * @param centralTax CGST, zero on an interstate supply
   * @param stateTax SGST, zero on an interstate supply
   * @param integratedTax IGST, zero on an intra-state supply
   */
  public record Line(
      BigDecimal taxable,
      BigDecimal ratePct,
      BigDecimal centralTax,
      BigDecimal stateTax,
      BigDecimal integratedTax) {

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

  /** CGST and SGST halves of a stated tax amount, at 4dp. */
  public record IntraStateSplit(BigDecimal centralTax, BigDecimal stateTax) {}

  /**
   * Shares a stated tax amount between CGST and SGST in the ratio this basis carries.
   *
   * <p>The journal posts what the vendor is owed, so the amount is the stated tax; the basis only
   * decides the ratio. When the basis already sums to the stated tax the halves are exactly its
   * own. Empty when the basis carries no intra-state tax to take a ratio from.
   */
  public java.util.Optional<IntraStateSplit> splitStated(BigDecimal statedTax) {
    BigDecimal central = BigDecimal.ZERO;
    BigDecimal state = BigDecimal.ZERO;
    for (Line line : lines) {
      central = central.add(line.centralTax());
      state = state.add(line.stateTax());
    }
    BigDecimal lineTax = central.add(state);
    if (statedTax == null || lineTax.signum() <= 0) {
      return java.util.Optional.empty();
    }
    BigDecimal cgst = statedTax.multiply(central).divide(lineTax, 4, RoundingMode.HALF_UP);
    return java.util.Optional.of(
        new IntraStateSplit(cgst, statedTax.subtract(cgst).setScale(4, RoundingMode.HALF_UP)));
  }
}
