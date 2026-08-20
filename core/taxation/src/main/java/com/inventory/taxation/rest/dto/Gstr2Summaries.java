package com.inventory.taxation.rest.dto;

import com.inventory.taxation.domain.gstr2.Gstr2B2bLine;
import com.inventory.taxation.domain.gstr2.Gstr2B2burLine;
import com.inventory.taxation.domain.model.GstHsnLine;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * The figures each GSTR-2 tab is headed with.
 *
 * <p>A tab holds one row per tax rate, so an invoice bearing two rates appears
 * twice. Counting rows would report that shop as having bought more invoices
 * than it did, and adding their values would charge it for one invoice twice --
 * on one real month that is 271,839 against the 240,704 actually filed.
 *
 * <p>So the count and the invoice value are taken over distinct invoices, while
 * the taxable value and the tax are summed over every row: each row carries only
 * its own rate's share of those, and together they make the whole.
 */
final class Gstr2Summaries {

  private Gstr2Summaries() {}

  static Gstr2SummaryDto empty() {
    return new Gstr2SummaryDto(0, 0, BigDecimal.ZERO, BigDecimal.ZERO,
        BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
  }

  static Gstr2SummaryDto ofB2b(List<Gstr2B2bLine> lines) {
    return new Gstr2SummaryDto(
        (int) lines.stream()
            .map(Gstr2B2bLine::getSupplierGstin)
            .filter(Gstr2Summaries::stated)
            .distinct()
            .count(),
        valuePerInvoice(lines, Gstr2B2bLine::getInvoiceNo, Gstr2B2bLine::getInvoiceValue).size(),
        total(valuePerInvoice(lines, Gstr2B2bLine::getInvoiceNo, Gstr2B2bLine::getInvoiceValue)
            .values()),
        sum(lines, Gstr2B2bLine::getTaxableValue),
        sum(lines, Gstr2B2bLine::getIntegratedTaxPaid),
        sum(lines, Gstr2B2bLine::getCentralTaxPaid),
        sum(lines, Gstr2B2bLine::getStateUtTaxPaid),
        sum(lines, Gstr2B2bLine::getCessAmount));
  }

  static Gstr2SummaryDto ofB2bur(List<Gstr2B2burLine> lines) {
    return new Gstr2SummaryDto(
        (int) lines.stream()
            .map(Gstr2B2burLine::getSupplierName)
            .filter(Gstr2Summaries::stated)
            .distinct()
            .count(),
        valuePerInvoice(lines, Gstr2B2burLine::getInvoiceNo, Gstr2B2burLine::getInvoiceValue).size(),
        total(valuePerInvoice(lines, Gstr2B2burLine::getInvoiceNo, Gstr2B2burLine::getInvoiceValue)
            .values()),
        sum(lines, Gstr2B2burLine::getTaxableValue),
        sum(lines, Gstr2B2burLine::getIntegratedTaxPaid),
        sum(lines, Gstr2B2burLine::getCentralTaxPaid),
        sum(lines, Gstr2B2burLine::getStateUtTaxPaid),
        sum(lines, Gstr2B2burLine::getCessAmount));
  }

  /**
   * The HSN summary counts nothing: it has no invoices and no suppliers, only
   * the goods and what was paid on them.
   */
  static Gstr2SummaryDto ofHsn(List<GstHsnLine> lines) {
    return new Gstr2SummaryDto(
        null,
        null,
        sum(lines, GstHsnLine::getTotalValue),
        sum(lines, GstHsnLine::getTaxableValue),
        sum(lines, GstHsnLine::getIntegratedTaxAmount),
        sum(lines, GstHsnLine::getCentralTaxAmount),
        sum(lines, GstHsnLine::getStateUtTaxAmount),
        sum(lines, GstHsnLine::getCessAmount));
  }

  /**
   * One value per invoice number, keeping the first stated.
   *
   * <p>Every row of an invoice repeats the same invoice value, so taking it once
   * is what makes the total the sum of the invoices rather than of the rates.
   */
  private static <T> Map<String, BigDecimal> valuePerInvoice(
      List<T> lines, Function<T, String> number, Function<T, BigDecimal> value) {
    Map<String, BigDecimal> byInvoice = new LinkedHashMap<>();
    for (T line : lines) {
      String key = number.apply(line);
      if (!stated(key)) {
        continue;
      }
      byInvoice.putIfAbsent(key, orZero(value.apply(line)));
    }
    return byInvoice;
  }

  private static <T> BigDecimal sum(List<T> lines, Function<T, BigDecimal> field) {
    return total(lines.stream().map(field).toList());
  }

  private static BigDecimal total(Iterable<BigDecimal> values) {
    BigDecimal sum = BigDecimal.ZERO;
    for (BigDecimal value : values) {
      sum = sum.add(orZero(value));
    }
    return sum;
  }

  private static BigDecimal orZero(BigDecimal value) {
    return value != null ? value : BigDecimal.ZERO;
  }

  private static boolean stated(String value) {
    return value != null && !value.isBlank();
  }
}
