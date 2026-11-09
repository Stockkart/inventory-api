package com.inventory.product.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.inventory.common.exception.ValidationException;
import com.inventory.product.domain.model.Inventory;
import com.inventory.product.domain.model.enums.SchemeType;
import com.inventory.product.rest.dto.request.UpdateInventoryRequest;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/**
 * A deal type sent without its scheme (the Edit dialog's deal-type dropdown on its own) must not
 * wipe the stored deal: it is dropped when it matches the stored type, and refused when it would
 * contradict the stored deal.
 */
class SchemeUpdateUtilsTest {

  private static Inventory lotWithPurchaseDeal(int payFor, int free) {
    Inventory lot = new Inventory();
    lot.setPurchaseSchemeType(SchemeType.FIXED_UNITS);
    lot.setPurchaseSchemePayFor(payFor);
    lot.setPurchaseSchemeFree(free);
    return lot;
  }

  private static Inventory lotWithSaleDeal(BigDecimal percentage) {
    Inventory lot = new Inventory();
    lot.setSchemeType(SchemeType.PERCENTAGE);
    lot.setSchemePercentage(percentage);
    return lot;
  }

  @Test
  void aPurchaseTypeMatchingTheStoredDealIsDropped() {
    UpdateInventoryRequest request = new UpdateInventoryRequest();
    request.setPurchaseSchemeType(SchemeType.FIXED_UNITS);

    SchemeUpdateUtils.dropOrRefuseTypeOnlyChanges(request, lotWithPurchaseDeal(19, 1));

    assertNull(request.getPurchaseSchemeType());
  }

  @Test
  void aPurchaseTypeContradictingTheStoredDealIsRefused() {
    UpdateInventoryRequest request = new UpdateInventoryRequest();
    request.setPurchaseSchemeType(SchemeType.PERCENTAGE);

    ValidationException e = assertThrows(ValidationException.class,
        () -> SchemeUpdateUtils.dropOrRefuseTypeOnlyChanges(request, lotWithPurchaseDeal(19, 1)));
    assertEquals(SchemeUpdateUtils.PURCHASE_TYPE_NEEDS_SCHEME, e.getMessage());
  }

  @Test
  void aPurchaseTypeOnALotWithoutADealIsRefused() {
    UpdateInventoryRequest request = new UpdateInventoryRequest();
    request.setPurchaseSchemeType(SchemeType.PERCENTAGE);

    assertThrows(ValidationException.class,
        () -> SchemeUpdateUtils.dropOrRefuseTypeOnlyChanges(request, new Inventory()));
  }

  @Test
  void aPurchaseTypeSentWithItsSchemeIsLeftAlone() {
    UpdateInventoryRequest request = new UpdateInventoryRequest();
    request.setPurchaseSchemeType(SchemeType.PERCENTAGE);
    request.setPurchaseSchemePercentage(new BigDecimal("10"));

    SchemeUpdateUtils.dropOrRefuseTypeOnlyChanges(request, lotWithPurchaseDeal(19, 1));

    assertEquals(SchemeType.PERCENTAGE, request.getPurchaseSchemeType());
    assertEquals(0, new BigDecimal("10").compareTo(request.getPurchaseSchemePercentage()));
  }

  @Test
  void aSaleTypeMatchingTheStoredDealIsDropped() {
    UpdateInventoryRequest request = new UpdateInventoryRequest();
    request.setSchemeType(SchemeType.PERCENTAGE);

    SchemeUpdateUtils.dropOrRefuseTypeOnlyChanges(request, lotWithSaleDeal(new BigDecimal("15")));

    assertNull(request.getSchemeType());
  }

  @Test
  void aSalePercentageContradictingTheStoredDealIsRefused() {
    Inventory lot = new Inventory();
    lot.setSchemeType(SchemeType.FIXED_UNITS);
    lot.setSchemePayFor(10);
    lot.setSchemeFree(2);
    UpdateInventoryRequest request = new UpdateInventoryRequest();
    request.setSchemeType(SchemeType.PERCENTAGE);

    ValidationException e = assertThrows(ValidationException.class,
        () -> SchemeUpdateUtils.dropOrRefuseTypeOnlyChanges(request, lot));
    assertEquals(SchemeUpdateUtils.SALE_TYPE_NEEDS_SCHEME, e.getMessage());
  }

  @Test
  void aSaleFreeUnitsTypeAloneStillClearsTheSaleScheme() {
    // The dialog clears a sale scheme as FIXED_UNITS with every part null, the same request as
    // the dropdown alone; it keeps working as a clear.
    UpdateInventoryRequest request = new UpdateInventoryRequest();
    request.setSchemeType(SchemeType.FIXED_UNITS);

    SchemeUpdateUtils.dropOrRefuseTypeOnlyChanges(request, lotWithSaleDeal(new BigDecimal("15")));

    assertEquals(SchemeType.FIXED_UNITS, request.getSchemeType());
  }

  @Test
  void aSaleSchemeSentInFullIsLeftAlone() {
    UpdateInventoryRequest request = new UpdateInventoryRequest();
    request.setSchemeType(SchemeType.FIXED_UNITS);
    request.setSchemePayFor(10);
    request.setSchemeFree(2);

    SchemeUpdateUtils.dropOrRefuseTypeOnlyChanges(request, lotWithSaleDeal(new BigDecimal("15")));

    assertEquals(SchemeType.FIXED_UNITS, request.getSchemeType());
    assertEquals(10, request.getSchemePayFor());
  }
}
