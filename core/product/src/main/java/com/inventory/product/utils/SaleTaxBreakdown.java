package com.inventory.product.utils;

import com.inventory.product.domain.model.Purchase;
import com.inventory.product.domain.model.PurchaseItem;
import com.inventory.product.rest.dto.response.GstRateRowDto;
import com.inventory.product.rest.dto.response.SaleTaxSummaryDto;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A bill's totals worked from its lines: the value before the additional discount, the taxable
 * value after it, and the CGST and SGST at each rate. The one place these figures are computed --
 * the printed invoice and every screen that shows a bill's totals read them from here.
 *
 * <p>Worked from the lines rather than read from the bill's header, the way GSTR-1 reads the same
 * sale. A line's amount includes its GST, so its taxable value is that amount with the GST taken
 * out at the line's own rate, and its tax is the rest. That holds for a line priced before tax,
 * where the GST was added on top, and for one sold at MRP, where it was already inside -- and it
 * is why bills saved before MRP-inclusive tax was stated still come out right.
 *
 * <p>Any rate works: each rate a line carries becomes its own row.
 */
public final class SaleTaxBreakdown {

  private SaleTaxBreakdown() {}

  /**
   * Empty when a line has no amount, or no line carries tax: there is then nothing to work from,
   * and the bill's own header totals are the ones to show.
   */
  public static Optional<SaleTaxSummaryDto> of(Purchase purchase) {
    if (purchase == null || purchase.getItems() == null || purchase.getItems().isEmpty()) {
      return Optional.empty();
    }
    Map<String, GstRateRowDto> rows = new LinkedHashMap<>();
    Map<String, BigDecimal> rowTax = new LinkedHashMap<>();
    List<BigDecimal> lineRates = new ArrayList<>();
    BigDecimal gross = BigDecimal.ZERO;
    BigDecimal taxable = BigDecimal.ZERO;
    for (PurchaseItem item : purchase.getItems()) {
      if (item.getTotalAmount() == null) {
        return Optional.empty();
      }
      BigDecimal amount = item.getTotalAmount();
      BigDecimal cgstPct = parseRate(item.getCgst());
      BigDecimal sgstPct = parseRate(item.getSgst());
      BigDecimal rate = cgstPct.add(sgstPct);
      BigDecimal lineTaxable = rate.signum() > 0
          ? amount.multiply(BigDecimal.valueOf(100)).divide(BigDecimal.valueOf(100).add(rate), 2, RoundingMode.HALF_UP)
          : amount;
      lineRates.add(taxableRate(item));
      // At the price the RATE column prints, so Total Amount is that column times quantity.
      gross = gross.add(CheckoutUtils.getTaxablePricePerUnit(item).setScale(2, RoundingMode.HALF_UP)
          .multiply(CheckoutUtils.getBillableQuantityAsDecimal(item))
          .setScale(2, RoundingMode.HALF_UP));
      taxable = taxable.add(lineTaxable);
      if (rate.signum() <= 0) {
        continue;
      }
      // Keyed on the rate's value, not its spelling: "9" and "9.00" are one rate.
      String key = cgstPct.stripTrailingZeros().toPlainString() + "|" + sgstPct.stripTrailingZeros().toPlainString();
      GstRateRowDto row = rows.computeIfAbsent(key, k -> new GstRateRowDto(
          cgstPct, sgstPct, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO));
      row.setTaxableValue(row.getTaxableValue().add(lineTaxable));
      rowTax.merge(key, amount.subtract(lineTaxable), BigDecimal::add);
    }
    if (rows.isEmpty()) {
      return Optional.empty();
    }
    // Split each rate's tax into CGST and SGST once, not per line: rounding each line's half up
    // and giving SGST the remainder drifts the two apart by a paisa a line.
    BigDecimal cgst = BigDecimal.ZERO;
    BigDecimal sgst = BigDecimal.ZERO;
    for (Map.Entry<String, GstRateRowDto> e : rows.entrySet()) {
      GstRateRowDto row = e.getValue();
      BigDecimal tax = rowTax.get(e.getKey());
      BigDecimal rowCgst = tax.multiply(row.getCgstPercent())
          .divide(row.getCgstPercent().add(row.getSgstPercent()), 2, RoundingMode.HALF_UP);
      row.setCgstAmount(rowCgst);
      row.setSgstAmount(tax.subtract(rowCgst));
      cgst = cgst.add(rowCgst);
      sgst = sgst.add(row.getSgstAmount());
    }
    BigDecimal grand = purchase.getGrandTotal() != null ? purchase.getGrandTotal() : taxable.add(cgst).add(sgst);
    return Optional.of(new SaleTaxSummaryDto(
        gross,
        gross.subtract(taxable),
        taxable,
        new ArrayList<>(rows.values()),
        cgst,
        sgst,
        grand.subtract(taxable).subtract(cgst).subtract(sgst),
        lineRates));
  }

  /**
   * The line's rate before tax, as the invoice's RATE column prints it. A line sold at MRP has its
   * GST inside the price, so it is taken out; any other line's rate is already before tax.
   */
  public static BigDecimal taxableRate(PurchaseItem item) {
    BigDecimal price = item.getPriceToRetail();
    BigDecimal rate = CheckoutUtils.combinedGstRate(item);
    if (price == null || !CheckoutUtils.isSellingAtMrp(item) || rate.signum() <= 0) {
      return price;
    }
    return price.multiply(BigDecimal.valueOf(100))
        .divide(BigDecimal.valueOf(100).add(rate), 2, RoundingMode.HALF_UP);
  }

  private static BigDecimal parseRate(String rate) {
    if (rate == null || rate.isBlank()) {
      return BigDecimal.ZERO;
    }
    try {
      return new BigDecimal(rate.trim());
    } catch (NumberFormatException e) {
      return BigDecimal.ZERO;
    }
  }
}
