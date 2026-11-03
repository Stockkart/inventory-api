package com.inventory.product.mapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.inventory.product.domain.model.Inventory;
import com.inventory.product.domain.model.enums.SchemeType;
import com.inventory.product.rest.dto.response.InventorySummaryDto;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

/**
 * Product search opens a lot's detail from its search row, so the row must carry the purchase
 * terms (deal and additional discount) the dialog shows and edits, as it already does the sale
 * terms.
 */
class InventoryMapperSummaryPurchaseTermsTest {

  private final InventoryMapper mapper = Mappers.getMapper(InventoryMapper.class);

  @Test
  void theSearchRowCarriesAFreeUnitsPurchaseDealAndDiscount() {
    Inventory lot = new Inventory();
    lot.setPurchaseSchemeType(SchemeType.FIXED_UNITS);
    lot.setPurchaseSchemePayFor(19);
    lot.setPurchaseSchemeFree(1);
    lot.setPurchaseAdditionalDiscount(new BigDecimal("9"));

    InventorySummaryDto row = mapper.toSummary(lot);

    assertEquals(SchemeType.FIXED_UNITS, row.getPurchaseSchemeType());
    assertEquals(19, row.getPurchaseSchemePayFor());
    assertEquals(1, row.getPurchaseSchemeFree());
    assertNull(row.getPurchaseSchemePercentage());
    assertEquals(0, new BigDecimal("9").compareTo(row.getPurchaseAdditionalDiscount()));
  }

  @Test
  void theSearchRowCarriesAPercentagePurchaseDeal() {
    Inventory lot = new Inventory();
    lot.setPurchaseSchemeType(SchemeType.PERCENTAGE);
    lot.setPurchaseSchemePercentage(new BigDecimal("12.5"));

    InventorySummaryDto row = mapper.toSummary(lot);

    assertEquals(SchemeType.PERCENTAGE, row.getPurchaseSchemeType());
    assertEquals(0, new BigDecimal("12.5").compareTo(row.getPurchaseSchemePercentage()));
  }
}
