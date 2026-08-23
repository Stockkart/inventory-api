package com.inventory.taxation.rest.dto;

import com.inventory.taxation.domain.gstr2.Gstr2B2bLine;
import com.inventory.taxation.domain.gstr2.Gstr2B2burLine;
import com.inventory.taxation.domain.model.GstHsnLine;
import com.inventory.taxation.summary.GstTotals;

import java.math.BigDecimal;
import java.util.List;

/**
 * The figures each GSTR-2 tab is headed with.
 *
 * <p>Which of {@link GstTotals}' three sums each figure takes, and why, is set
 * out there: the count names distinct invoices, the invoice value is taken once
 * per invoice, and the taxable value and tax are summed over every rate row.
 */
final class Gstr2Summaries {

  private Gstr2Summaries() {}

  static Gstr2SummaryDto empty() {
    return new Gstr2SummaryDto(0, 0, BigDecimal.ZERO, BigDecimal.ZERO,
        BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
  }

  static Gstr2SummaryDto ofB2b(List<Gstr2B2bLine> lines) {
    return new Gstr2SummaryDto(
        GstTotals.countDistinct(lines, Gstr2B2bLine::getSupplierGstin),
        GstTotals.countDistinct(lines, Gstr2B2bLine::getInvoiceNo),
        GstTotals.sumPerDistinct(lines, Gstr2B2bLine::getInvoiceNo, Gstr2B2bLine::getInvoiceValue),
        GstTotals.sum(lines, Gstr2B2bLine::getTaxableValue),
        GstTotals.sum(lines, Gstr2B2bLine::getIntegratedTaxPaid),
        GstTotals.sum(lines, Gstr2B2bLine::getCentralTaxPaid),
        GstTotals.sum(lines, Gstr2B2bLine::getStateUtTaxPaid),
        GstTotals.sum(lines, Gstr2B2bLine::getCessAmount));
  }

  static Gstr2SummaryDto ofB2bur(List<Gstr2B2burLine> lines) {
    return new Gstr2SummaryDto(
        GstTotals.countDistinct(lines, Gstr2B2burLine::getSupplierName),
        GstTotals.countDistinct(lines, Gstr2B2burLine::getInvoiceNo),
        GstTotals.sumPerDistinct(
            lines, Gstr2B2burLine::getInvoiceNo, Gstr2B2burLine::getInvoiceValue),
        GstTotals.sum(lines, Gstr2B2burLine::getTaxableValue),
        GstTotals.sum(lines, Gstr2B2burLine::getIntegratedTaxPaid),
        GstTotals.sum(lines, Gstr2B2burLine::getCentralTaxPaid),
        GstTotals.sum(lines, Gstr2B2burLine::getStateUtTaxPaid),
        GstTotals.sum(lines, Gstr2B2burLine::getCessAmount));
  }

  /**
   * The HSN summary counts nothing: it has no invoices and no suppliers, only
   * the goods and what was paid on them.
   */
  static Gstr2SummaryDto ofHsn(List<GstHsnLine> lines) {
    return new Gstr2SummaryDto(
        null,
        null,
        GstTotals.sum(lines, GstHsnLine::getTotalValue),
        GstTotals.sum(lines, GstHsnLine::getTaxableValue),
        GstTotals.sum(lines, GstHsnLine::getIntegratedTaxAmount),
        GstTotals.sum(lines, GstHsnLine::getCentralTaxAmount),
        GstTotals.sum(lines, GstHsnLine::getStateUtTaxAmount),
        GstTotals.sum(lines, GstHsnLine::getCessAmount));
  }
}
