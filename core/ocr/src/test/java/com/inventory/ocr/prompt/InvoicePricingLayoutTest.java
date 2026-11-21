package com.inventory.ocr.prompt;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.inventory.ocr.api.InvoicePricingLayout;
import org.junit.jupiter.api.Test;

class InvoicePricingLayoutTest {

  @Test
  void wholesalerPromptMapsPtsPtrMrpAndForbidsTakingCostFromMrp() {
    String text = InvoicePrompts.text(InvoicePricingLayout.WHOLESALER);
    assertTrue(text.contains("PTS"));
    assertTrue(text.contains("PTR"));
    assertTrue(text.contains("must NEVER be taken from MRP"));
    assertTrue(text.contains("lowest unit price"));
    assertFalse(text.contains("Price/Unit"));
  }

  @Test
  void retailerPromptMapsCostFromPricePerUnitAndSellFromMrp() {
    String text = InvoicePrompts.text(InvoicePricingLayout.RETAILER);
    assertTrue(text.contains("Price/Unit"));
    assertTrue(text.contains("selling price equals MRP"));
    assertTrue(text.contains("split equally"));
    assertFalse(text.contains("lowest unit price"));
    assertFalse(text.contains("must NEVER be taken from MRP"));
  }

  @Test
  void orDefaultFallsBackToWholesaler() {
    org.junit.jupiter.api.Assertions.assertEquals(
        InvoicePricingLayout.WHOLESALER, InvoicePricingLayout.orDefault(null));
  }
}
