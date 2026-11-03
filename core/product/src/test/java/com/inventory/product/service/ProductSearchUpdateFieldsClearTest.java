package com.inventory.product.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.inventory.product.rest.dto.request.UpdateInventoryRequest;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Clearing a field needs the same per-member grant as setting it. */
class ProductSearchUpdateFieldsClearTest {

  @Test
  void clearedFieldsAreCheckedUnderTheGrantForSettingThem() {
    UpdateInventoryRequest r = new UpdateInventoryRequest();
    r.setClearFields(new LinkedHashSet<>(List.of("location", "saleScheme", "purchaseScheme")));

    assertEquals(Set.of("location", "schemeType", "purchaseSchemeType"),
        ProductSearchUpdateFields.fromUpdateRequest(r));
  }
}
