package com.inventory.product.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.inventory.product.domain.model.PurchaseItem;
import java.math.BigDecimal;
import java.math.RoundingMode;
import org.junit.jupiter.api.Test;

class CheckoutUtilsTaxablePriceTest {

  private static PurchaseItem line(String mrp, String rate, String cgst, String sgst) {
    PurchaseItem item = new PurchaseItem();
    item.setMaximumRetailPrice(new BigDecimal(mrp));
    item.setPriceToRetail(new BigDecimal(rate));
    item.setQuantity(new BigDecimal("3"));
    item.setCgst(cgst);
    item.setSgst(sgst);
    return item;
  }

  private static BigDecimal taxablePrice(PurchaseItem item) {
    return CheckoutUtils.getTaxablePricePerUnit(item).setScale(2, RoundingMode.HALF_UP);
  }

  @Test
  void atMrpTakesTheGstInsideThePriceOut() {
    // BABY SHAMPOO 100ML on T001317: MRP 115 at 5% holds 5.48 of GST.
    assertEquals(new BigDecimal("109.52"), taxablePrice(line("115", "115", "2.5", "2.5")));
    // BABY OIL 200ML: MRP 270 at 18% holds 41.19 of GST.
    assertEquals(new BigDecimal("228.81"), taxablePrice(line("270.00", "270", "9", "9")));
  }

  @Test
  void belowMrpIsAlreadyBeforeTaxAndIsLeftAlone() {
    // SEPTILIN on T001317: the rate is already the taxable price, and GST is added on top.
    assertEquals(new BigDecimal("195.81"), taxablePrice(line("257", "195.81", "2.5", "2.5")));
  }

  @Test
  void atMrpWithoutARateHasNothingToTakeOut() {
    // A BASIC bill clears the line's rates; the MRP is then all price.
    assertEquals(new BigDecimal("115.00"), taxablePrice(line("115", "115", null, null)));
  }

  @Test
  void rateSpellingDoesNotMatter() {
    assertEquals(taxablePrice(line("501", "501", "9", "9")), taxablePrice(line("501", "501", "9.00", "9.00")));
  }
}
