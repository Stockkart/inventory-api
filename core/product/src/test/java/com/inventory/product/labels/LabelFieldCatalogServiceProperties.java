package com.inventory.product.labels;

// Feature: barcode-label-layout, Property 1: Catalog shape, order and determinism

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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * Property 1: Catalog shape, order and determinism.
 *
 * <p><b>Validates: Requirements 1.1, 1.2, 1.7</b>
 *
 * <p>For any shop (any {@code shopType}, including null) and any vertical schema, the catalog is
 * deterministic, every {@code fieldKey} is unique, labels are 1–60 chars, {@code sourceGroup} and
 * {@code valueType} are set, {@code availableForShopTypes} has 1–3 values, every static key is
 * present regardless of shop type, groups appear in the order {@code product, pricing, lot,
 * vertical, shop}, and vertical keys keep schema order.
 */
public class LabelFieldCatalogServiceProperties {

  private static final String SHOP_ID = "shop-prop";

  private static final List<String> TYPE_POOL =
      List.of(
          "string", "enum", "number", "money", "currency", "date", "percent", "percentage",
          "boolean", "list", "object", "file", "", "weird");

  /** All inputs the catalog depends on, generated together. */
  public record Scenario(
      ShopType shopType,
      String verticalId,
      String pluginVersion,
      VerticalSchema schema,
      List<String> rateNames) {}

  @Property(tries = 100)
  void catalogHasStableShapeOrderAndIsDeterministic(@ForAll("scenarios") Scenario scenario) {
    LabelFieldCatalogService service = service(scenario);
    Shop shop = shop(scenario, scenario.shopType());

    FieldCatalog first = service.catalog(shop);
    FieldCatalog second = service.catalog(shop);

    // Determinism (Req 1.1): identical result for identical inputs.
    assertThat(second.fields()).isEqualTo(first.fields());
    assertThat(second.effectiveShopType()).isEqualTo(first.effectiveShopType());

    List<PrintableField> fields = first.fields();
    List<String> keys = fields.stream().map(PrintableField::fieldKey).toList();

    // Unique keys (Req 1.2).
    assertThat(new HashSet<>(keys)).hasSameSizeAs(keys);

    // Shape of every field (Req 1.2).
    for (PrintableField f : fields) {
      assertThat(f.fieldKey()).isNotBlank();
      assertThat(f.label()).isNotNull();
      assertThat(f.label().length()).isBetween(1, 60);
      assertThat(f.sourceGroup()).isNotNull();
      assertThat(f.valueType()).isNotNull();
      assertThat(f.availableForShopTypes()).isNotNull();
      assertThat(f.availableForShopTypes().size()).isBetween(1, 3);
    }

    // Every static core/pricing/lot/shop key present regardless of shop type (Req 1.2, 1.7).
    Set<String> keySet = new HashSet<>(keys);
    assertThat(keySet).containsAll(staticKeys());

    // Static keys do not depend on shopType: same shop with every other shop type yields the
    // exact same key list.
    List<ShopType> otherTypes = new ArrayList<>(List.of(ShopType.values()));
    otherTypes.add(null);
    for (ShopType other : otherTypes) {
      List<String> otherKeys =
          service.catalog(shop(scenario, other)).fields().stream()
              .map(PrintableField::fieldKey)
              .toList();
      assertThat(otherKeys).isEqualTo(keys);
    }

    // Group order non-decreasing in product, pricing, lot, vertical, shop (Req 1.7).
    for (int i = 1; i < fields.size(); i++) {
      assertThat(fields.get(i).sourceGroup().ordinal())
          .as("group order at index %d (%s after %s)", i, keys.get(i), keys.get(i - 1))
          .isGreaterThanOrEqualTo(fields.get(i - 1).sourceGroup().ordinal());
    }

    // Vertical keys appear in schema order (inventory entity first, then product entity).
    List<String> schemaKeys = schemaKeysInOrder(scenario.schema());
    int lastIdx = -1;
    for (PrintableField f : fields) {
      if (f.sourceGroup() != SourceGroup.VERTICAL) {
        continue;
      }
      String schemaKey = f.fieldKey().substring(LabelFieldKeys.VERTICAL_PREFIX.length());
      int idx = schemaKeys.indexOf(schemaKey);
      assertThat(idx).as("vertical key %s comes from the schema", schemaKey).isGreaterThanOrEqualTo(0);
      assertThat(idx).as("vertical key %s keeps schema order", schemaKey).isGreaterThan(lastIdx);
      lastIdx = idx;
    }
  }

  // ---- generators ----------------------------------------------------------------------------

  @Provide
  public Arbitrary<Scenario> scenarios() {
    Arbitrary<ShopType> shopType = Arbitraries.of(ShopType.class).injectNull(0.25);
    Arbitrary<String> verticalId =
        Arbitraries.oneOf(
            Arbitraries.just(null),
            Arbitraries.just(""),
            Arbitraries.just("   "),
            Arbitraries.strings().alpha().ofMinLength(1).ofMaxLength(12));
    Arbitrary<String> pluginVersion =
        Arbitraries.of("1.0.0", "2.3.1", "9.9.9").injectNull(0.2);
    Arbitrary<List<String>> rateNames =
        Arbitraries.strings()
            .alpha()
            .ofMinLength(1)
            .ofMaxLength(10)
            .list()
            .uniqueElements()
            .ofMaxSize(5);
    return Combinators.combine(shopType, verticalId, pluginVersion, schemas(), rateNames)
        .as(Scenario::new);
  }

  private Arbitrary<VerticalSchema> schemas() {
    Arbitrary<List<VerticalSchemaField>> fields = schemaFields().list().ofMaxSize(8);
    Arbitrary<Boolean> hasInventory = Arbitraries.of(true, true, true, false);
    Arbitrary<Boolean> hasProduct = Arbitraries.of(true, false);
    return Combinators.combine(fields, fields, hasInventory, hasProduct)
        .as(
            (inv, prod, withInv, withProd) -> {
              Map<String, VerticalEntitySchema> entities = new LinkedHashMap<>();
              if (withInv) {
                entities.put(LabelFieldCatalogService.INVENTORY_ENTITY, entity(inv));
              }
              if (withProd) {
                entities.put(LabelFieldCatalogService.PRODUCT_ENTITY, entity(prod));
              }
              VerticalSchema schema = new VerticalSchema();
              schema.setEntities(entities.isEmpty() ? null : entities);
              return schema;
            });
  }

  private Arbitrary<VerticalSchemaField> schemaFields() {
    // Keys deliberately drawn from a small pool plus core/lot backing names so collisions,
    // dedup and exclusion are exercised; blank keys are skipped by the service.
    Arbitrary<String> key =
        Arbitraries.oneOf(
            Arbitraries.of("rack", "schedule", "mfgDate", "size", "brand", "colour", "qty"),
            Arbitraries.of(new ArrayList<>(LabelFieldKeys.CORE_LOT_BACKING_PROPERTIES)),
            Arbitraries.strings().alpha().ofMinLength(1).ofMaxLength(10),
            Arbitraries.just(""),
            Arbitraries.just(null));
    Arbitrary<String> apiKey =
        Arbitraries.oneOf(
            Arbitraries.just(null),
            Arbitraries.just(""),
            Arbitraries.of(new ArrayList<>(LabelFieldKeys.CORE_LOT_BACKING_PROPERTIES)),
            Arbitraries.strings().alpha().ofMinLength(1).ofMaxLength(10));
    Arbitrary<String> label =
        Arbitraries.oneOf(
            Arbitraries.just(null),
            Arbitraries.just(""),
            Arbitraries.just("   "),
            Arbitraries.strings().ofMinLength(1).ofMaxLength(90));
    Arbitrary<String> type = Arbitraries.of(TYPE_POOL).injectNull(0.1);
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

  // ---- helpers -------------------------------------------------------------------------------

  public static LabelFieldCatalogService service(Scenario scenario) {
    ShopRepository shopRepository = mock(ShopRepository.class);
    PricingRepository pricingRepository = mock(PricingRepository.class);
    SchemaLoader schemaLoader = mock(SchemaLoader.class);
    when(pricingRepository.findDistinctRateNamesByShopId(SHOP_ID))
        .thenReturn(scenario.rateNames());
    when(schemaLoader.load(anyString(), any())).thenReturn(scenario.schema());
    return new LabelFieldCatalogService(
        shopRepository, pricingRepository, schemaLoader, new VerticalValueTypeMapper());
  }

  public static Shop shop(Scenario scenario, ShopType shopType) {
    Shop shop = new Shop();
    shop.setShopId(SHOP_ID);
    shop.setShopType(shopType);
    shop.setVerticalId(scenario.verticalId());
    shop.setPluginVersion(scenario.pluginVersion());
    return shop;
  }

  private static VerticalEntitySchema entity(List<VerticalSchemaField> fields) {
    VerticalEntitySchema e = new VerticalEntitySchema();
    e.setFields(fields);
    return e;
  }

  private static List<String> staticKeys() {
    List<String> keys = new ArrayList<>();
    LabelLayoutDefaults.coreFields().forEach(f -> keys.add(f.fieldKey()));
    LabelLayoutDefaults.pricingFields().forEach(f -> keys.add(f.fieldKey()));
    LabelLayoutDefaults.lotFields().forEach(f -> keys.add(f.fieldKey()));
    LabelLayoutDefaults.shopFields().forEach(f -> keys.add(f.fieldKey()));
    return keys;
  }

  /**
   * Printable schema keys in catalog traversal order: inventory entity fields, then product entity,
   * first occurrence wins. Fields the catalog can never print (blank key, unmappable type, or
   * backed by a core/lot property) do not take part in ordering.
   */
  private static List<String> schemaKeysInOrder(VerticalSchema schema) {
    VerticalValueTypeMapper mapper = new VerticalValueTypeMapper();
    List<String> keys = new ArrayList<>();
    if (schema.getEntities() == null) {
      return keys;
    }
    for (String entityName :
        List.of(LabelFieldCatalogService.INVENTORY_ENTITY, LabelFieldCatalogService.PRODUCT_ENTITY)) {
      VerticalEntitySchema entity = schema.getEntities().get(entityName);
      if (entity == null || entity.getFields() == null) {
        continue;
      }
      for (VerticalSchemaField f : entity.getFields()) {
        String key = f.getKey();
        if (key == null || key.isBlank() || mapper.map(f.getType()).isEmpty()) {
          continue;
        }
        String apiKey = f.getApiKey() == null || f.getApiKey().isBlank() ? key : f.getApiKey();
        if (LabelFieldKeys.CORE_LOT_BACKING_PROPERTIES.contains(apiKey)) {
          continue;
        }
        if (!keys.contains(key)) {
          keys.add(key);
        }
      }
    }
    return keys;
  }
}
