package com.inventory.product.domain.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class InvoiceSettingsDefaultsTest {

  @Test
  void resolveOverlaysPartialStoredFlags() {
    InvoiceFieldVisibility stored = new InvoiceFieldVisibility();
    stored.setShowScheme(false);
    stored.setShowHsn(false);

    InvoiceFieldVisibility resolved =
        InvoiceSettingsDefaults.resolve(stored, InvoiceSettingsDefaults.regularFields());

    assertFalse(resolved.getShowScheme());
    assertFalse(resolved.getShowHsn());
    assertTrue(resolved.getShowSellerDetails());
    assertTrue(resolved.getShowTaxDetails());
  }

  @Test
  void basicDefaultsHideTaxAndParties() {
    InvoiceFieldVisibility basic = InvoiceSettingsDefaults.basicFields();
    assertFalse(basic.getShowSellerDetails());
    assertFalse(basic.getShowBuyerDetails());
    assertFalse(basic.getShowTaxDetails());
    assertEquals(true, basic.getShowBatch());
  }
}
