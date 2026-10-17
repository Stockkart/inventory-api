package com.inventory.product.gridlayout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.inventory.common.exception.ValidationException;
import java.util.List;
import org.junit.jupiter.api.Test;

class GridColumnCatalogTest {

  private final List<GridColumnDefinition> catalog = GridLayoutTestFixtures.catalog();

  @Test
  void productEntryOrderMatchesTheGrid() {
    assertThat(catalog.stream().map(GridColumnDefinition::key))
        .containsExactly(
            "rowNo", "barcode", "field:companyName", "product", "field:batchNo",
            "field:expiryDate", "qty", "packaging", "location", "field:sellDirect",
            "rate", "sellPrice", "pts", "ptr", "mrp", "saleDealType", "saleScheme", "saleDisc",
            "purchaseDealType", "purchaseScheme", "purchaseDisc", "discApplicability", "hsn", "gstRate", "cgst",
            "sgst", "actions");
  }

  @Test
  void requiredSchemaFieldsAndSellDirectAreMandatory() {
    assertThat(def("field:batchNo").mandatory()).isTrue();
    assertThat(def("field:sellDirect").mandatory()).isTrue();
    assertThat(def("field:companyName").mandatory()).isFalse();
    assertThat(def("field:expiryDate").mandatory()).isFalse();
    assertThat(def("product").mandatory()).isTrue();
    assertThat(def("barcode").mandatory()).isFalse();
  }

  @Test
  void onlyRowNumberAndActionsArePinned() {
    assertThat(catalog.stream().filter(c -> !c.movable()).map(GridColumnDefinition::key))
        .containsExactly("rowNo", "actions");
  }

  @Test
  void noSchemaStillGivesStaticColumns() {
    List<GridColumnDefinition> bare = new GridColumnCatalog().columnsFor(GridIds.PRODUCT_ENTRY, null);
    assertThat(bare).extracting(GridColumnDefinition::key).doesNotContain("field:batchNo").contains("product");
  }

  @Test
  void unknownGridIsRejected() {
    assertThatThrownBy(() -> new GridColumnCatalog().columnsFor("nope", null))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("Unknown grid");
  }

  private GridColumnDefinition def(String key) {
    return catalog.stream().filter(c -> c.key().equals(key)).findFirst().orElseThrow();
  }
}
