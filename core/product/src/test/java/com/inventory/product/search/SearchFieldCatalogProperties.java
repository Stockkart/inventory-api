package com.inventory.product.search;

// Feature: advanced-product-search, Property 1: Search field catalog shape

import static org.assertj.core.api.Assertions.assertThat;

import com.inventory.product.domain.model.Shop;
import com.inventory.product.labels.FieldCatalog;
import com.inventory.product.labels.FieldUsage;
import com.inventory.product.labels.LabelFieldCatalogService;
import com.inventory.product.labels.LabelFieldCatalogServiceProperties;
import com.inventory.product.labels.LabelFieldCatalogServiceProperties.Scenario;
import com.inventory.product.labels.LabelFieldKeys;
import com.inventory.product.labels.PrintableField;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * For any shop and vertical schema: every SEARCH field carries a spec with a path; its operators
 * are exactly those of its type; enum fields list values; keys are unique; the core search fields
 * are always present; every schema field marked searchable appears (once, even when it shadows a
 * core field) and no unsearchable schema field does; the LABEL and CARD views are unchanged by the
 * search metadata.
 *
 * <p><b>Validates: Requirements R1.2, R1.3, R1.5, R1.6</b>
 */
class SearchFieldCatalogProperties {

  private static final Set<String> CORE_SEARCH_KEYS =
      Set.of(
          LabelFieldKeys.PRODUCT_NAME,
          LabelFieldKeys.COMPANY_NAME,
          LabelFieldKeys.BARCODE_TEXT,
          LabelFieldKeys.HSN,
          LabelFieldKeys.LOCATION,
          LabelFieldKeys.BILLING_MODE,
          LabelFieldKeys.STOCK_STATE,
          LabelFieldKeys.PURCHASE_DATE,
          LabelFieldKeys.RECEIVED_DATE,
          LabelFieldKeys.CURRENT_COUNT);

  @Property(tries = 100)
  void searchViewIsWellFormed(@ForAll("scenarios") Scenario scenario) {
    LabelFieldCatalogService service = LabelFieldCatalogServiceProperties.service(scenario);
    Shop shop = LabelFieldCatalogServiceProperties.shop(scenario, scenario.shopType());
    FieldCatalog catalog = service.catalog(shop);
    List<PrintableField> search = catalog.forUsage(FieldUsage.SEARCH);

    Set<String> keys = new HashSet<>();
    for (PrintableField f : search) {
      assertThat(keys.add(f.fieldKey())).as("unique key %s", f.fieldKey()).isTrue();
      SearchSpec spec = f.searchSpec();
      assertThat(spec).as("spec of %s", f.fieldKey()).isNotNull();
      assertThat(spec.path()).isNotBlank();
      assertThat(spec.operators()).isEqualTo(spec.type().operators());
      if (spec.type() == SearchFieldType.ENUM) {
        assertThat(spec.enumValues()).isNotEmpty();
        assertThat(spec.facetable()).isTrue();
      }
      if (spec.source() == SearchSource.EXTENSION) {
        assertThat(spec.path()).startsWith("ext.");
      }
    }
    // Non-search fields never carry a spec.
    catalog.fields().stream()
        .filter(f -> !f.usableFor(FieldUsage.SEARCH))
        .forEach(f -> assertThat(f.searchSpec()).as("no spec on %s", f.fieldKey()).isNull());

    // Core search fields always present (R1.2).
    assertThat(keys).containsAll(CORE_SEARCH_KEYS);

    // Every searchable schema field is present exactly once; unsearchable ones are absent (R1.3, R1.5).
    for (var entity : scenario.schema().getEntities() == null ? List.<com.inventory.pluginengine.schema.VerticalEntitySchema>of()
        : scenario.schema().getEntities().values()) {
      if (entity == null || entity.getFields() == null) continue;
      for (var sf : entity.getFields()) {
        if (sf == null || sf.getKey() == null || sf.getKey().isBlank()) continue;
        String apiKey = sf.getApiKey() == null || sf.getApiKey().isBlank() ? sf.getKey() : sf.getApiKey();
        boolean shadowsCore = LabelFieldKeys.CORE_LOT_BACKING_PROPERTIES.contains(apiKey);
        boolean mappable = new com.inventory.product.labels.VerticalValueTypeMapper().map(sf.getType()).isPresent();
        String verticalKey = LabelFieldKeys.verticalKey(sf.getKey());
        if (shadowsCore) {
          assertThat(keys).as("shadowing field %s never appears as vertical.*", sf.getKey()).doesNotContain(verticalKey);
        } else if (mappable && Boolean.TRUE.equals(sf.getSearchable())) {
          // first occurrence wins in the catalog; a later duplicate key is dropped
          assertThat(catalog.find(verticalKey)).isPresent();
        } else if (mappable) {
          assertThat(keys).as("%s is not searchable", verticalKey).doesNotContain(verticalKey);
        }
      }
    }

    // Search metadata leaves the sticker and card views untouched (R1.6).
    List<String> labelKeys = catalog.forUsage(FieldUsage.LABEL).stream().map(PrintableField::fieldKey).toList();
    assertThat(labelKeys).doesNotContain(LabelFieldKeys.STOCK_STATE);
    List<String> cardKeys = catalog.forUsage(FieldUsage.CARD).stream().map(PrintableField::fieldKey).toList();
    assertThat(cardKeys).doesNotContain(LabelFieldKeys.STOCK_STATE);
    assertThat(cardKeys).contains(LabelFieldKeys.BILLING_MODE);
  }

  @Provide
  Arbitrary<Scenario> scenarios() {
    return new LabelFieldCatalogServiceProperties().scenarios();
  }
}
