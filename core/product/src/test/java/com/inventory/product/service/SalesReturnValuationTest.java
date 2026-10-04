package com.inventory.product.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.inventory.product.domain.model.PurchaseItem;
import com.inventory.product.domain.model.enums.BillingMode;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class SalesReturnValuationTest {

  private static void assertMoney(String expected, BigDecimal actual) {
    assertEquals(0, new BigDecimal(expected).compareTo(actual),
        "expected " + expected + " but was " + actual);
  }

  /** One unit sold at {@code rate} before tax, below MRP, at {@code halfGst} each side. */
  private static PurchaseItem sold(String rate, String halfGst) {
    PurchaseItem item = new PurchaseItem();
    item.setPriceToRetail(new BigDecimal(rate));
    item.setMaximumRetailPrice(new BigDecimal(rate).multiply(BigDecimal.valueOf(2)));
    item.setQuantity(BigDecimal.ONE);
    item.setBaseQuantity(1);
    item.setCgst(halfGst);
    item.setSgst(halfGst);
    return item;
  }

  @Test
  void aLocalReturnReversesCgstAndSgst() {
    SalesReturnValuation.LineAmounts line =
        SalesReturnValuation.lineAmounts(sold("100", "9"), 1, 1, BillingMode.REGULAR, false);

    assertMoney("9.00", line.cgst());
    assertMoney("9.00", line.sgst());
    assertMoney("0", line.igst());
    assertMoney("118.00", line.lineTotal());
  }

  @Test
  void anInterstateReturnReversesIgstAtTheCombinedRate() {
    SalesReturnValuation.LineAmounts line =
        SalesReturnValuation.lineAmounts(sold("100", "9"), 1, 1, BillingMode.REGULAR, true);

    assertMoney("0", line.cgst());
    assertMoney("0", line.sgst());
    assertMoney("18.00", line.igst());
    assertMoney("118.00", line.lineTotal());

    SalesReturnValuation.AmountTotals totals = SalesReturnValuation.aggregate(List.of(line));
    assertMoney("18.00", totals.igstTotal());
    assertMoney("118", totals.returnTotal());
  }

  @Test
  void aRateWrittenWithAPercentSignStillCounts() {
    SalesReturnValuation.LineAmounts line =
        SalesReturnValuation.lineAmounts(sold("100", "9%"), 1, 1, BillingMode.REGULAR, false);

    assertMoney("9.00", line.cgst());
  }
}
