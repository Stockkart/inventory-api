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
  void requiredFieldsCannotBeHiddenButCanMove() {
    for (String key : List.of("field:batchNo", "field:sellDirect", "product", "qty", "packaging", "location")) {
      assertThat(def(key).required()).as(key).isTrue();
      assertThat(def(key).hideable()).as(key).isFalse();
      assertThat(def(key).reorderable()).as(key).isTrue();
      assertThat(def(key).position()).as(key).isEqualTo(GridColumnPosition.ANY);
    }
    for (String key : List.of("field:companyName", "field:expiryDate", "barcode", "hsn")) {
      assertThat(def(key).required()).as(key).isFalse();
      assertThat(def(key).hideable()).as(key).isTrue();
    }
  }

  @Test
  void structuralColumnsArePinnedAndNotRequired() {
    assertThat(catalog.stream().filter(c -> !c.reorderable()).map(GridColumnDefinition::key))
        .containsExactly("rowNo", "actions");
    assertThat(def("rowNo").position()).isEqualTo(GridColumnPosition.FIRST);
    assertThat(def("actions").position()).isEqualTo(GridColumnPosition.LAST);
    assertThat(def("rowNo").required()).isFalse();
    assertThat(def("rowNo").hideable()).isFalse();
  }

  @Test
  void everyColumnHasADisplayLabel() {
    assertThat(def("product").label()).isEqualTo("Product");
    assertThat(def("rowNo").label()).isEqualTo("#");
    // Schema fields without a label fall back to the key in words, like the frontend.
    assertThat(def("field:batchNo").label()).isEqualTo("Batch No");
    assertThat(def("field:expiryDate").label()).isEqualTo("Expiry Date");
    assertThat(catalog).allSatisfy(c -> assertThat(c.label()).isNotBlank());
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
