package com.inventory.product.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.inventory.product.domain.model.Refund;
import com.inventory.product.domain.model.RefundItem;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class CreditNoteFiguresTest {

  private static RefundItem line(String taxable, String cgst, String sgst, String igst) {
    RefundItem item = new RefundItem();
    item.setTaxableValue(new BigDecimal(taxable));
    item.setCgstAmount(new BigDecimal(cgst));
    item.setSgstAmount(new BigDecimal(sgst));
    item.setIgstAmount(new BigDecimal(igst));
    return item;
  }

  private static Refund refund(String refundAmount) {
    Refund refund = new Refund();
    refund.setRefundAmount(new BigDecimal(refundAmount));
    return refund;
  }

  @Test
  void aRefundThatRecordedItsTotalsIsStatedAsRecorded() {
    Refund refund = refund("322");
    refund.setTaxableTotal(new BigDecimal("299.55"));
    refund.setCgstAmount(new BigDecimal("10.99"));
    refund.setSgstAmount(new BigDecimal("10.99"));
    refund.setRoundOff(new BigDecimal("0.47"));

    CreditNoteFigures.Totals totals = CreditNoteFigures.totals(refund, List.of());

    assertEquals(new BigDecimal("299.55"), totals.taxableTotal());
    assertEquals(new BigDecimal("10.99"), totals.cgstAmount());
    assertEquals(new BigDecimal("10.99"), totals.sgstAmount());
    assertEquals(new BigDecimal("0.00"), totals.igstAmount());
    assertEquals(new BigDecimal("21.98"), totals.taxTotal());
    assertEquals(new BigDecimal("0.47"), totals.roundOff());
    assertEquals(new BigDecimal("322.00"), totals.grandTotal());
  }

  @Test
  void anOlderRefundIsStatedFromItsLinesWithTheRoundOffLeftOver() {
    Refund refund = refund("322");

    CreditNoteFigures.Totals totals = CreditNoteFigures.totals(
        refund,
        List.of(line("100.00", "6.00", "6.00", "0"), line("199.55", "4.99", "4.99", "0")));

    assertEquals(new BigDecimal("299.55"), totals.taxableTotal());
    assertEquals(new BigDecimal("10.99"), totals.cgstAmount());
    assertEquals(new BigDecimal("10.99"), totals.sgstAmount());
    assertEquals(new BigDecimal("21.98"), totals.taxTotal());
    assertEquals(new BigDecimal("0.47"), totals.roundOff());
    assertEquals(new BigDecimal("322.00"), totals.grandTotal());
  }

  @Test
  void anInterstateReturnStatesItsTaxAsIgst() {
    Refund refund = refund("112");
    refund.setTaxableTotal(new BigDecimal("100"));
    refund.setIgstAmount(new BigDecimal("12"));
    refund.setInterstate(true);

    CreditNoteFigures.Totals totals = CreditNoteFigures.totals(refund, List.of());

    assertEquals(new BigDecimal("0.00"), totals.cgstAmount());
    assertEquals(new BigDecimal("12.00"), totals.igstAmount());
    assertEquals(new BigDecimal("12.00"), totals.taxTotal());
    assertEquals(new BigDecimal("0.00"), totals.roundOff());
  }

  @Test
  void anOlderInterstateRefundTakesItsIgstFromTheLines() {
    Refund refund = refund("112");

    CreditNoteFigures.Totals totals =
        CreditNoteFigures.totals(refund, List.of(line("100", "0", "0", "12")));

    assertEquals(new BigDecimal("12.00"), totals.igstAmount());
    assertEquals(new BigDecimal("12.00"), totals.taxTotal());
    assertEquals(new BigDecimal("0.00"), totals.roundOff());
  }

  @Test
  void aLineRateIsBothHalvesTogether() {
    assertEquals(new BigDecimal("12"), CreditNoteFigures.lineRatePct("6", "6"));
    assertEquals(new BigDecimal("5.0"), CreditNoteFigures.lineRatePct("2.5%", " 2.5 "));
    assertEquals(BigDecimal.ZERO, CreditNoteFigures.lineRatePct(null, ""));
  }
}
