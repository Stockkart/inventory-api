package com.inventory.taxation.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.inventory.product.domain.model.Inventory;
import com.inventory.product.domain.model.PurchaseItem;
import org.junit.jupiter.api.Test;

class Gstr1HsnUqcTest {

  private static Inventory lot(String baseUnit) {
    Inventory inv = new Inventory();
    inv.setBaseUnit(baseUnit);
    return inv;
  }

  private static PurchaseItem line(String baseUnit) {
    PurchaseItem item = new PurchaseItem();
    item.setBaseUnit(baseUnit);
    return item;
  }

  @Test
  void reportsTheProductsRealQuantityCode() {
    assertEquals("PCS-PIECES", Gstr1DataAggregator.resolveUqc(lot("PCS"), null));
    assertEquals("TBS-TABLETS", Gstr1DataAggregator.resolveUqc(lot("TBS"), null));
    assertEquals("BTL-BOTTLES", Gstr1DataAggregator.resolveUqc(lot("BTL"), null));
  }

  @Test
  void prefersTheLotOverTheSaleLine() {
    // The lot's base unit is hydrated from the product and is the authority; the
    // sale line carries its own copy taken at the time of sale.
    assertEquals("PCS-PIECES", Gstr1DataAggregator.resolveUqc(lot("PCS"), line("BTL")));
  }

  @Test
  void fallsBackToTheSaleLineWhenTheLotIsGone() {
    assertEquals("BTL-BOTTLES", Gstr1DataAggregator.resolveUqc(null, line("BTL")));
    assertEquals("BTL-BOTTLES", Gstr1DataAggregator.resolveUqc(lot(null), line("BTL")));
  }

  @Test
  void fallsBackToOthOthersWhenTheUnitIsAbsentOrNotAUqc() {
    assertEquals("OTH-OTHERS", Gstr1DataAggregator.resolveUqc(null, null));
    assertEquals("OTH-OTHERS", Gstr1DataAggregator.resolveUqc(lot(""), line("")));
    assertEquals("OTH-OTHERS", Gstr1DataAggregator.resolveUqc(lot("STRIP"), null));
  }

  @Test
  void toleratesSurroundingWhitespaceAndCase() {
    assertEquals("PCS-PIECES", Gstr1DataAggregator.resolveUqc(lot("  pcs "), null));
  }
}
