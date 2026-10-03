package com.inventory.product.labels;

// Feature: barcode-label-layout, Property 2: Vertical schema fields map to printable fields exactly
// when mappable and not duplicated

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.inventory.pluginengine.schema.VerticalEntitySchema;
import com.inventory.pluginengine.schema.VerticalSchema;
import com.inventory.pluginengine.schema.VerticalSchemaField;
import com.inventory.pricing.domain.repository.PricingRepository;
import com.inventory.product.domain.model.Shop;
import com.inventory.product.domain.model.enums.ShopType;
import com.inventory.product.domain.repository.ShopRepository;
import com.inventory.product.service.vertical.SchemaLoader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tuple;

/**
 * Property 2: Vertical schema fields map to printable fields exactly when mappable and not
 * duplicated.
 *
 * <p><b>Validates: Requirements 1.3, 1.5</b>
 *
 * <p>For any list of {@code inventory} entity schema fields with arbitrary {@code key}, {@code
 * apiKey}, {@code label} (including blank) and {@code type}, the vertical set of the catalog
 * contains exactly one {@code vertical.<key>} entry for each field whose type maps to a value type
 * and whose effective apiKey ({@code apiKey}, or {@code key} when blank) is not, case-sensitively,
 * a core/lot backing property. That entry carries the mapped value type, the trimmed schema label
 * (or the key when blank) truncated to 60 chars, all three shop types and the effective apiKey.
 * Fields of unmappable types or duplicating a core/lot property produce no vertical entry while
 * every core/lot static entry remains present.
 */
class LabelFieldCatalogVerticalProperties {

  private static final String SHOP_ID = "shop-vertical-prop";

  private static final Set<ShopType> ALL_SHOP_TYPES =
      Set.of(ShopType.RETAILER, ShopType.DISTRIBUTOR, ShopType.WHOLESALER);

  private static final List<String> MAPPABLE_TYPES =
      List.of(
          "string", "enum", "number", "money", "currency", "date", "percent", "percentage");

  private static final List<String> UNMAPPABLE_TYPES = List.of("boolean", "list", "object");

  /** Case variants of core/lot backing properties: must NOT be excluded (case-sensitive). */
  private static final List<String> CASE_VARIANTS =
      List.of(
          "BatchNo", "BATCHNO", "ExpiryDate", "EXPIRYDATE", "Name", "CompanyName", "Hsn",
          "ReceivedDate", "Description", "BaseUnit");

  @Property(tries = 100)
  void verticalFieldsMapExactlyWhenMappableAndNotDuplicated(
      @ForAll("schemaFieldLists") List<VerticalSchemaField> schemaFields) {
    LabelFieldCatalogService service = service(schemaFields);
    FieldCatalog catalog = service.catalog(shop());

    List<PrintableField> vertical =
        catalog.fields().stream().filter(f -> f.sourceGroup() == SourceGroup.VERTICAL).toList();
    Map<String, PrintableField> verticalByKey = new LinkedHashMap<>();
    for (PrintableField f : vertical) {
      assertThat(verticalByKey.put(f.fieldKey(), f))
          .as("vertical key %s appears once", f.fieldKey())
          .isNull();
    }

    // Expected: first eligible occurrence per schema key.
    Map<String, VerticalSchemaField> expected = expectedBySchemaKey(schemaFields);

    assertThat(verticalByKey.keySet())
        .containsExactlyInAnyOrderElementsOf(
            expected.keySet().stream().map(LabelFieldKeys::verticalKey).toList());

    VerticalValueTypeMapper mapper = new VerticalValueTypeMapper();
    for (Map.Entry<String, VerticalSchemaField> e : expected.entrySet()) {
      String key = e.getKey();
      VerticalSchemaField sf = e.getValue();
      PrintableField pf = verticalByKey.get(LabelFieldKeys.verticalKey(key));
      assertThat(pf).as("vertical entry for %s", key).isNotNull();
      assertThat(pf.valueType()).isEqualTo(mapper.map(sf.getType()).orElseThrow());
      assertThat(pf.label()).isEqualTo(expectedLabel(sf));
      assertThat(pf.availableForShopTypes()).containsExactlyInAnyOrderElementsOf(ALL_SHOP_TYPES);
      assertThat(pf.schemaApiKey()).isEqualTo(effectiveApiKey(sf));
    }

    // Fields that are unmappable or duplicate a core/lot property produce no vertical entry.
    for (VerticalSchemaField sf : schemaFields) {
      if (sf.getKey() == null || sf.getKey().isBlank()) {
        continue;
      }
      boolean mappable = mapper.map(sf.getType()).isPresent();
      boolean duplicate = LabelFieldKeys.CORE_LOT_BACKING_PROPERTIES.contains(effectiveApiKey(sf));
      if ((!mappable || duplicate) && !expected.containsKey(sf.getKey())) {
        assertThat(verticalByKey)
            .as("no vertical entry for %s (mappable=%s, duplicate=%s)", sf.getKey(), mappable,
                duplicate)
            .doesNotContainKey(LabelFieldKeys.verticalKey(sf.getKey()));
      }
    }

    // Every core/lot static entry remains present.
    List<String> keys = catalog.fields().stream().map(PrintableField::fieldKey).toList();
    for (PrintableField f : LabelLayoutDefaults.coreFields()) {
      assertThat(keys).contains(f.fieldKey());
    }
    for (PrintableField f : LabelLayoutDefaults.lotFields()) {
      assertThat(keys).contains(f.fieldKey());
    }
  }

  // ---- generators ----------------------------------------------------------------------------

  @Provide
  Arbitrary<List<VerticalSchemaField>> schemaFieldLists() {
    return schemaFields().list().ofMaxSize(12);
  }

  private Arbitrary<VerticalSchemaField> schemaFields() {
    Arbitrary<String> key =
        Arbitraries.frequencyOf(
            Tuple.of(4, Arbitraries.strings().alpha().ofMinLength(1).ofMaxLength(10)),
            Tuple.of(
                3, Arbitraries.of(new ArrayList<>(LabelFieldKeys.CORE_LOT_BACKING_PROPERTIES))),
            Tuple.of(2, Arbitraries.of(CASE_VARIANTS)),
            Tuple.of(2, Arbitraries.of("rack", "schedule", "mfgDate", "size")),
            Tuple.of(1, Arbitraries.of("", "  ")),
            Tuple.of(1, Arbitraries.just(null)));
    Arbitrary<String> apiKey =
        Arbitraries.frequencyOf(
            Tuple.of(3, Arbitraries.just(null)),
            Tuple.of(1, Arbitraries.of("", "   ")),
            Tuple.of(
                3, Arbitraries.of(new ArrayList<>(LabelFieldKeys.CORE_LOT_BACKING_PROPERTIES))),
            Tuple.of(2, Arbitraries.of(CASE_VARIANTS)),
            Tuple.of(3, Arbitraries.strings().alpha().ofMinLength(1).ofMaxLength(10)));
    Arbitrary<String> label =
        Arbitraries.frequencyOf(
            Tuple.of(2, Arbitraries.just(null)),
            Tuple.of(1, Arbitraries.of("", "   ")),
            Tuple.of(4, Arbitraries.strings().ofMinLength(1).ofMaxLength(40)),
            Tuple.of(2, Arbitraries.strings().alpha().ofMinLength(61).ofMaxLength(120)),
            Tuple.of(1, Arbitraries.strings().alpha().ofMinLength(1).ofMaxLength(20)
                .map(s -> "  " + s + "  ")));
    Arbitrary<String> type =
        Arbitraries.frequencyOf(
            Tuple.of(6, Arbitraries.of(MAPPABLE_TYPES)),
            Tuple.of(2, Arbitraries.of(UNMAPPABLE_TYPES)),
            Tuple.of(1, Arbitraries.strings().alpha().ofMinLength(1).ofMaxLength(8)),
            Tuple.of(1, Arbitraries.just(null)));
    return Combinators.combine(key, apiKey, label, type)
        .as(
            (k, a, l, t) -> {
              VerticalSchemaField f = new VerticalSchemaField();
              f.setKey(k);
              f.setApiKey(a);
              f.setLabel(l);
              f.setType(t);
              return f;
            });
  }

  // ---- expected model ------------------------------------------------------------------------

  private static String effectiveApiKey(VerticalSchemaField f) {
    return f.getApiKey() == null || f.getApiKey().isBlank() ? f.getKey() : f.getApiKey();
  }

  private static String expectedLabel(VerticalSchemaField f) {
    String label =
        f.getLabel() == null || f.getLabel().isBlank() ? f.getKey() : f.getLabel().trim();
    return label.length() > LabelFieldCatalogService.MAX_LABEL_LENGTH
        ? label.substring(0, LabelFieldCatalogService.MAX_LABEL_LENGTH)
        : label;
  }

  /** Schema key → first schema field that is printable (mappable type, not a core/lot dup). */
  private static Map<String, VerticalSchemaField> expectedBySchemaKey(
      List<VerticalSchemaField> fields) {
    VerticalValueTypeMapper mapper = new VerticalValueTypeMapper();
    Map<String, VerticalSchemaField> result = new LinkedHashMap<>();
    for (VerticalSchemaField f : fields) {
      String key = f.getKey();
      if (key == null || key.isBlank()) {
        continue;
      }
      Optional<ValueType> type = mapper.map(f.getType());
      if (type.isEmpty()) {
        continue;
      }
      if (LabelFieldKeys.CORE_LOT_BACKING_PROPERTIES.contains(effectiveApiKey(f))) {
        continue;
      }
      result.putIfAbsent(key, f);
    }
    return result;
  }

  // ---- helpers -------------------------------------------------------------------------------

  private static LabelFieldCatalogService service(List<VerticalSchemaField> inventoryFields) {
    VerticalEntitySchema entity = new VerticalEntitySchema();
    entity.setFields(inventoryFields);
    VerticalSchema schema = new VerticalSchema();
    schema.setEntities(Map.of(LabelFieldCatalogService.INVENTORY_ENTITY, entity));

    ShopRepository shopRepository = mock(ShopRepository.class);
    PricingRepository pricingRepository = mock(PricingRepository.class);
    SchemaLoader schemaLoader = mock(SchemaLoader.class);
    when(pricingRepository.findDistinctRateNamesByShopId(SHOP_ID)).thenReturn(List.of());
    when(schemaLoader.load(anyString(), any())).thenReturn(schema);
    return new LabelFieldCatalogService(
        shopRepository, pricingRepository, schemaLoader, new VerticalValueTypeMapper());
  }

  private static Shop shop() {
    Shop shop = new Shop();
    shop.setShopId(SHOP_ID);
    shop.setShopType(ShopType.RETAILER);
    shop.setVerticalId("medical");
    shop.setPluginVersion("1.0.0");
    return shop;
  }
}
