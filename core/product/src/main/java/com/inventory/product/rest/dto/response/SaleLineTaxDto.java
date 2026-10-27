package com.inventory.product.rest.dto.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * One line's share of a bill's tax, as {@link com.inventory.product.utils.SaleTaxBreakdown} works
 * it: the line's amount with the GST taken out at its own rate, and that GST. Shown next to the
 * line while billing (Scan &amp; Sell), so the cashier sees product-wise taxable value without
 * waiting for the printed invoice.
 *
 * <p>The tax here is the line's combined GST, not split into CGST and SGST: the halves are split
 * once per rate row (see {@link GstRateRowDto}) so they do not drift apart line by line.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SaleLineTaxDto {
  private BigDecimal taxableValue;
  /** GST on the line (CGST + SGST, or IGST interstate); zero on an untaxed line. */
  private BigDecimal taxAmount;
  /** The line's combined GST rate, e.g. 5 for 2.5 + 2.5; zero when it carries none. */
  private BigDecimal ratePercent;
}
