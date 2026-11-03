package com.inventory.product.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.inventory.product.domain.model.Location;
import org.junit.jupiter.api.Test;

/** {@code ShopSummary.addressLine} (HR payslips, R10.2). */
class ShopServiceAddressLineTest {

  @Test
  void joinsTheStoredPartsInInvoiceOrder() {
    Location location = new Location("12 MG Road", " ", "Karnataka", "Bengaluru", "560001", "IN");

    assertEquals("12 MG Road, Bengaluru, Karnataka, 560001", ShopService.addressLine(location));
  }

  @Test
  void noAddressIsNull() {
    assertNull(ShopService.addressLine(null));
    assertNull(ShopService.addressLine(new Location()));
  }
}
