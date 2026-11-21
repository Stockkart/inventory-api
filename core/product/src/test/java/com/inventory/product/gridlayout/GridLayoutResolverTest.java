package com.inventory.product.gridlayout;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class GridLayoutResolverTest {

  private final GridLayoutResolver resolver = new GridLayoutResolver();
  private final List<GridColumnDefinition> catalog = GridLayoutTestFixtures.catalog();

  @Test
  void defaultsAreCatalogOrderAtDefaultWidths() {
    List<GridColumnState> defaults = resolver.defaults(catalog);
    assertThat(defaults).extracting(GridColumnState::key).containsExactlyElementsOf(keys(catalog));
    assertThat(defaults).noneMatch(GridColumnState::hidden);
  }

  @Test
  void keepsSavedOrderAndFillsMissingColumnsAfterTheirCatalogNeighbour() {
    List<GridColumnState> out =
        resolver.resolve(
            List.of(
                new GridColumnState("qty", 90, false),
                new GridColumnState("product", 300, false),
                new GridColumnState("barcode", 150, true)),
            catalog);
    List<String> keys = out.stream().map(GridColumnState::key).toList();
    assertThat(keys).hasSameSizeAs(catalog).startsWith("rowNo", "qty").endsWith("actions");
    assertThat(keys.indexOf("product")).isLessThan(keys.indexOf("barcode"));
    assertThat(keys.indexOf("packaging")).isEqualTo(keys.indexOf("qty") + 1);
    assertThat(out).contains(new GridColumnState("barcode", 150, true));
  }

  @Test
  void dropsUnknownAndDuplicateKeysClampsWidthsAndShowsMandatoryColumns() {
    List<GridColumnState> out =
        resolver.resolve(
            List.of(
                new GridColumnState("ghost", 100, false),
                new GridColumnState("product", 5000, true),
                new GridColumnState("product", 100, false),
                new GridColumnState("hsn", 0, false),
                new GridColumnState("actions", 10, true)),
            catalog);
    assertThat(out).extracting(GridColumnState::key).doesNotContain("ghost").doesNotHaveDuplicates();
    assertThat(out).contains(new GridColumnState("product", 600, false));
    assertThat(out).contains(new GridColumnState("hsn", 110, false));
    assertThat(out.get(out.size() - 1)).isEqualTo(new GridColumnState("actions", 48, false));
  }

  private static List<String> keys(List<GridColumnDefinition> defs) {
    return defs.stream().map(GridColumnDefinition::key).toList();
  }
}
