package com.inventory.product.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.inventory.common.constants.PurchaseTaxTreatment;
import com.inventory.product.rest.dto.request.CreateInventoryItemRequest;
import com.inventory.user.domain.model.Vendor;
import com.inventory.user.domain.repository.VendorRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class PurchaseTaxTreatmentResolverTest {

  private final VendorRepository vendorRepository = mock(VendorRepository.class);
  private final PurchaseTaxTreatmentResolver resolver =
      new PurchaseTaxTreatmentResolver(vendorRepository);

  private static CreateInventoryItemRequest row(String cost, String mrp) {
    CreateInventoryItemRequest item = new CreateInventoryItemRequest();
    item.setCostPrice(cost == null ? null : new BigDecimal(cost));
    item.setMaximumRetailPrice(mrp == null ? null : new BigDecimal(mrp));
    return item;
  }

  @Test
  void costAtMrpIsInclusive() {
    // PARAS A00000999: cost 99, MRP 99.
    assertEquals(PurchaseTaxTreatment.INCLUSIVE,
        PurchaseTaxTreatmentResolver.fromLines(List.of(row("99", "99"))));
  }

  @Test
  void costBelowMrpIsExclusive() {
    // HIORA K toothpaste: cost 108.06, MRP 160.
    assertEquals(PurchaseTaxTreatment.EXCLUSIVE,
        PurchaseTaxTreatmentResolver.fromLines(List.of(row("108.06", "160"))));
  }

  @Test
  void linesThatDisagreeOrLackAnMrpDecideNothing() {
    assertNull(PurchaseTaxTreatmentResolver.fromLines(
        List.of(row("99", "99"), row("108.06", "160"))));
    assertNull(PurchaseTaxTreatmentResolver.fromLines(List.of(row("50", null))));
  }

  @Test
  void theBillWinsThenTheLinesThenTheVendor() {
    Vendor vendor = new Vendor();
    vendor.setDefaultTaxTreatment(PurchaseTaxTreatment.INCLUSIVE);
    when(vendorRepository.findById("v1")).thenReturn(Optional.of(vendor));
    List<CreateInventoryItemRequest> belowMrp = List.of(row("108.06", "160"));

    assertEquals(PurchaseTaxTreatmentResolver.Source.STATED,
        resolver.resolve(PurchaseTaxTreatment.INCLUSIVE, "v1", belowMrp).source());
    PurchaseTaxTreatmentResolver.Resolved fromLines = resolver.resolve(null, "v1", belowMrp);
    assertEquals(PurchaseTaxTreatment.EXCLUSIVE, fromLines.treatment());
    assertEquals(PurchaseTaxTreatmentResolver.Source.LINES, fromLines.source());
    assertEquals(PurchaseTaxTreatmentResolver.Source.VENDOR,
        resolver.resolve(null, "v1", List.of(row("50", null))).source());
  }
}
