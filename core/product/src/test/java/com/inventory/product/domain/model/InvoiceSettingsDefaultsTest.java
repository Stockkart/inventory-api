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
    assertTrue(resolved.getShowShopGstin());
    assertTrue(resolved.getShowCustomerPan());
  }

  @Test
  void regularDefaultsShowPartyChildren() {
    InvoiceFieldVisibility regular = InvoiceSettingsDefaults.regularFields();
    assertTrue(regular.getShowShopName());
    assertTrue(regular.getShowShopPan());
    assertTrue(regular.getShowShopDlNo());
    assertTrue(regular.getShowShopFssai());
    assertTrue(regular.getShowCustomerName());
    assertTrue(regular.getShowCustomerDlNo());
  }

  @Test
  void basicDefaultsShowCorePartiesHideCompliance() {
    InvoiceFieldVisibility basic = InvoiceSettingsDefaults.basicFields();
    assertTrue(basic.getShowShopName());
    assertTrue(basic.getShowShopAddress());
    assertTrue(basic.getShowShopPhone());
    assertFalse(basic.getShowShopPan());
    assertFalse(basic.getShowShopDlNo());
    assertFalse(basic.getShowShopFssai());
    assertTrue(basic.getShowCustomerName());
    assertTrue(basic.getShowCustomerAddress());
    assertTrue(basic.getShowCustomerPhone());
    assertFalse(basic.getShowCustomerGstin());
    assertFalse(basic.getShowCustomerPan());
    assertFalse(basic.getShowCustomerDlNo());
  }

  @Test
  void resolveKeepsMissingPartyChildrenFromModeDefaults() {
    InvoiceFieldVisibility stored = new InvoiceFieldVisibility();
    stored.setShowSellerDetails(true);
    stored.setShowShopGstin(false);

    InvoiceFieldVisibility resolved =
        InvoiceSettingsDefaults.resolve(stored, InvoiceSettingsDefaults.regularFields());

    assertTrue(resolved.getShowSellerDetails());
    assertFalse(resolved.getShowShopGstin());
    assertTrue(resolved.getShowShopPan());
    assertTrue(resolved.getShowShopDlNo());
    assertTrue(resolved.getShowCustomerAddress());
  }

  @Test
  void copyPreservesPartyChildren() {
    InvoiceFieldVisibility src = InvoiceSettingsDefaults.regularFields();
    src.setShowShopPan(false);
    src.setShowCustomerEmail(false);

    InvoiceFieldVisibility copied = InvoiceSettingsDefaults.copy(src);
    assertFalse(copied.getShowShopPan());
    assertFalse(copied.getShowCustomerEmail());
    assertTrue(copied.getShowShopDlNo());
  }

  @Test
  void basicDefaultsShowPartiesHideTax() {
    InvoiceFieldVisibility basic = InvoiceSettingsDefaults.basicFields();
    assertTrue(basic.getShowSellerDetails());
    assertTrue(basic.getShowBuyerDetails());
    assertFalse(basic.getShowTaxDetails());
    assertFalse(basic.getShowShopGstin());
    assertEquals(true, basic.getShowBatch());
  }
}
