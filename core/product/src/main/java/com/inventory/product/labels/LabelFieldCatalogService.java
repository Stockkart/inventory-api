package com.inventory.product.labels;

import com.inventory.common.exception.ResourceNotFoundException;
import com.inventory.pluginengine.schema.VerticalEntitySchema;
import com.inventory.pluginengine.schema.VerticalSchema;
import com.inventory.pluginengine.schema.VerticalSchemaField;
import com.inventory.pricing.domain.repository.PricingRepository;
import com.inventory.product.domain.model.Shop;
import com.inventory.product.domain.model.enums.ShopType;
import com.inventory.product.domain.repository.ShopRepository;
import com.inventory.product.search.SearchSource;
import com.inventory.product.search.SearchSpec;
import com.inventory.product.service.vertical.SchemaLoader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Assembles the Field_Catalog for a shop (label Req 1.1–1.7, 1.9, 3.5, 4.6; card Req 1.2, 1.3, 1.8).
 *
 * <p>Group order is fixed to {@code product, pricing, lot, vertical, shop}. Within the product,
 * pricing and lot groups the sticker-and-card fields come first and the card-only fields follow, so
 * the {@link FieldUsage#LABEL} view of the catalog is byte-for-byte what it was before cards
 * existed. The pricing group is the static set plus one named-rate field per distinct {@code
 * Pricing.rates[].name} found for the shop. The vertical group is derived from the shop's vertical
 * schema ({@code inventory} entity fields, then {@code product} entity fields); when the shop has
 * no vertical or the schema cannot be loaded, the group is empty and {@link
 * FieldCatalog#verticalSchemaLoaded()} is {@code false}. The final list is deduplicated by {@code
 * fieldKey}, keeping the first occurrence.
 */
@Service
@Slf4j
public class LabelFieldCatalogService {

  /** Vertical schema entity whose fields describe an inventory lot. */
  static final String INVENTORY_ENTITY = "inventory";

  /** Vertical schema entity whose fields describe a product. */
  static final String PRODUCT_ENTITY = "product";

  /** Maximum length of a vertical field label (longer schema labels are truncated). */
  static final int MAX_LABEL_LENGTH = 60;

  /** Card values of vertical fields live in the summary DTO's {@code verticalFields} map. */
  static final String VERTICAL_ITEM_PATH_PREFIX = "verticalFields.";

  /** In the search pipeline the joined extension document is unwound under this prefix. */
  static final String SEARCH_EXTENSION_PATH_PREFIX = "ext.";

  /** Schema storage value meaning "lives in the extension collection". */
  static final String EXTENSION_STORAGE = "extension";

  private static final Set<ShopType> ALL_SHOP_TYPES =
      Set.of(ShopType.RETAILER, ShopType.DISTRIBUTOR, ShopType.WHOLESALER);

  private final ShopRepository shopRepository;
  private final PricingRepository pricingRepository;
  private final SchemaLoader schemaLoader;
  private final VerticalValueTypeMapper valueTypeMapper;

  public LabelFieldCatalogService(
      ShopRepository shopRepository,
      PricingRepository pricingRepository,
      SchemaLoader schemaLoader,
      VerticalValueTypeMapper valueTypeMapper) {
    this.shopRepository = shopRepository;
    this.pricingRepository = pricingRepository;
    this.schemaLoader = schemaLoader;
    this.valueTypeMapper = valueTypeMapper;
  }

  /**
   * Loads the shop and assembles its catalog.
   *
   * @throws ResourceNotFoundException when no shop exists with the given id
   */
  public FieldCatalog catalog(String shopId) {
    Shop shop =
        shopRepository
            .findById(shopId)
            .orElseThrow(() -> new ResourceNotFoundException("Shop", "shopId", shopId));
    return catalog(shop);
  }

  /** Assembles the catalog for an already-loaded shop. */
  public FieldCatalog catalog(Shop shop) {
    String shopId = shop.getShopId();

    List<PrintableField> ordered = new ArrayList<>();
    ordered.addAll(LabelLayoutDefaults.coreFields());
    ordered.addAll(LabelLayoutDefaults.cardProductFields());
    ordered.addAll(LabelLayoutDefaults.pricingFields());
    ordered.addAll(namedRateFields(shopId));
    ordered.addAll(LabelLayoutDefaults.cardPricingFields());
    VerticalSchemaResult vertical = loadVerticalSchema(shop);
    ordered.addAll(relocateShadowedLotFields(LabelLayoutDefaults.lotFields(), vertical));
    ordered.addAll(LabelLayoutDefaults.cardLotFields());
    ordered.addAll(LabelLayoutDefaults.searchLotFields());

    ordered.addAll(verticalFields(vertical.inventoryFields()));
    ordered.addAll(verticalFields(vertical.productFields()));

    ordered.addAll(LabelLayoutDefaults.shopFields());

    List<PrintableField> fields = dedupByFieldKey(ordered);
    ShopType effectiveShopType = LabelLayoutDefaults.effectiveShopType(shop.getShopType());

    return new FieldCatalog(
        fields,
        LabelLayoutDefaults.STICKER_SIZES,
        effectiveShopType,
        vertical.loaded(),
        vertical.inventoryFields(),
        vertical.productFields());
  }

  // ---- pricing -------------------------------------------------------------------------------

  private List<PrintableField> namedRateFields(String shopId) {
    List<String> rateNames = pricingRepository.findDistinctRateNamesByShopId(shopId);
    if (rateNames == null || rateNames.isEmpty()) {
      return List.of();
    }
    List<PrintableField> result = new ArrayList<>(rateNames.size());
    for (String rateName : rateNames) {
      if (StringUtils.hasText(rateName)) {
        result.add(LabelLayoutDefaults.namedRateField(rateName));
      }
    }
    return result;
  }

  // ---- vertical ------------------------------------------------------------------------------

  /** Outcome of loading the shop's vertical schema; empty field lists when not loaded. */
  private record VerticalSchemaResult(
      boolean loaded,
      List<VerticalSchemaField> inventoryFields,
      List<VerticalSchemaField> productFields) {

    static VerticalSchemaResult notLoaded() {
      return new VerticalSchemaResult(false, List.of(), List.of());
    }
  }

  private VerticalSchemaResult loadVerticalSchema(Shop shop) {
    String verticalId = shop.getVerticalId();
    if (!StringUtils.hasText(verticalId)) {
      log.debug("Shop {} has no verticalId — vertical label fields skipped", shop.getShopId());
      return VerticalSchemaResult.notLoaded();
    }
    try {
      VerticalSchema schema = schemaLoader.load(verticalId, shop.getPluginVersion());
      if (schema == null) {
        log.warn(
            "Vertical schema {} v{} for shop {} resolved to null — vertical label fields skipped",
            verticalId,
            shop.getPluginVersion(),
            shop.getShopId());
        return VerticalSchemaResult.notLoaded();
      }
      return new VerticalSchemaResult(
          true,
          entityFields(schema, INVENTORY_ENTITY),
          entityFields(schema, PRODUCT_ENTITY));
    } catch (RuntimeException e) {
      log.warn(
          "Could not load vertical schema {} v{} for shop {} — vertical label fields skipped: {}",
          verticalId,
          shop.getPluginVersion(),
          shop.getShopId(),
          e.getMessage());
      return VerticalSchemaResult.notLoaded();
    }
  }

  private static List<VerticalSchemaField> entityFields(VerticalSchema schema, String entity) {
    Map<String, VerticalEntitySchema> entities = schema.getEntities();
    if (entities == null) {
      return List.of();
    }
    VerticalEntitySchema entitySchema = entities.get(entity);
    if (entitySchema == null || entitySchema.getFields() == null) {
      return List.of();
    }
    return entitySchema.getFields().stream().filter(f -> f != null).toList();
  }

  /**
   * Converts schema fields to printable fields in schema order, skipping types that are not
   * printable and fields that are already exposed through a core/lot field.
   */
  private List<PrintableField> verticalFields(List<VerticalSchemaField> schemaFields) {
    List<PrintableField> result = new ArrayList<>();
    for (VerticalSchemaField field : schemaFields) {
      toPrintableField(field).ifPresent(result::add);
    }
    return result;
  }

  private Optional<PrintableField> toPrintableField(VerticalSchemaField field) {
    String key = field.getKey();
    if (!StringUtils.hasText(key)) {
      return Optional.empty();
    }
    Optional<ValueType> valueType = valueTypeMapper.map(field.getType());
    if (valueType.isEmpty()) {
      return Optional.empty();
    }
    String apiKey = StringUtils.hasText(field.getApiKey()) ? field.getApiKey() : key;
    if (LabelFieldKeys.CORE_LOT_BACKING_PROPERTIES.contains(apiKey)) {
      return Optional.empty();
    }
    String label = StringUtils.hasText(field.getLabel()) ? field.getLabel().trim() : key;
    if (label.length() > MAX_LABEL_LENGTH) {
      label = label.substring(0, MAX_LABEL_LENGTH);
    }
    PrintableField printable =
        new PrintableField(
            LabelFieldKeys.verticalKey(key),
            label,
            SourceGroup.VERTICAL,
            valueType.get(),
            ALL_SHOP_TYPES,
            apiKey,
            PrintableField.DEFAULT_USAGES,
            Sensitivity.PUBLIC,
            VERTICAL_ITEM_PATH_PREFIX + apiKey);
    Optional<SearchSpec> search = verticalSearchSpec(field, apiKey);
    return Optional.of(search.map(printable::withSearch).orElse(printable));
  }

  // ---- search metadata (advanced-product-search R1.3, R1.5) ----------------------------------

  /**
   * A schema field marked {@code searchable} becomes a search field: enums are facetable, {@code
   * sortable} follows the schema, the Mongo path points into the joined extension document (or the
   * core lot when the schema stores it on the core document).
   */
  private static Optional<SearchSpec> verticalSearchSpec(VerticalSchemaField field, String apiKey) {
    if (!Boolean.TRUE.equals(field.getSearchable())) {
      return Optional.empty();
    }
    boolean extension = isExtensionStorage(field);
    SearchSource source = extension ? SearchSource.EXTENSION : SearchSource.LOT;
    String path = extension ? SEARCH_EXTENSION_PATH_PREFIX + apiKey : apiKey;
    boolean sortable = Boolean.TRUE.equals(field.getSortable());
    String type = field.getType() == null ? "" : field.getType().trim().toLowerCase();
    return switch (type) {
      case "enum" -> {
        List<SearchSpec.EnumValue> values =
            field.getValues() == null
                ? List.of()
                : field.getValues().stream().filter(StringUtils::hasText).map(SearchSpec.EnumValue::of).toList();
        yield values.isEmpty()
            ? Optional.of(SearchSpec.text(source, path, true, sortable))
            : Optional.of(SearchSpec.enumeration(source, path, sortable, values));
      }
      case "number", "money", "currency", "percent", "percentage" ->
          Optional.of(SearchSpec.number(source, path, sortable));
      case "date" -> Optional.of(SearchSpec.date(source, path, sortable));
      default -> Optional.of(SearchSpec.text(source, path, false, sortable));
    };
  }

  /**
   * Core lot fields that the vertical stores in its extension document (pharmacy batch and expiry)
   * are matched on the extension path, with the schema's own {@code searchable}/{@code sortable}
   * flags. The field keeps its core key so saved card/label layouts are unaffected.
   */
  private static List<PrintableField> relocateShadowedLotFields(
      List<PrintableField> lotFields, VerticalSchemaResult vertical) {
    if (!vertical.loaded()) {
      return lotFields;
    }
    Map<String, VerticalSchemaField> extensionByApiKey = new LinkedHashMap<>();
    for (VerticalSchemaField f : vertical.inventoryFields()) {
      String apiKey = StringUtils.hasText(f.getApiKey()) ? f.getApiKey() : f.getKey();
      if (StringUtils.hasText(apiKey) && isExtensionStorage(f)) {
        extensionByApiKey.put(apiKey, f);
      }
    }
    if (extensionByApiKey.isEmpty()) {
      return lotFields;
    }
    List<PrintableField> out = new ArrayList<>(lotFields.size());
    for (PrintableField field : lotFields) {
      VerticalSchemaField shadow = extensionByApiKey.get(field.itemPath());
      if (shadow == null || field.searchSpec() == null) {
        out.add(field);
        continue;
      }
      SearchSpec relocated =
          field.searchSpec()
              .relocate(SearchSource.EXTENSION, SEARCH_EXTENSION_PATH_PREFIX + field.itemPath())
              .withSortable(Boolean.TRUE.equals(shadow.getSortable()) || field.searchSpec().sortable());
      // A shadowed field the schema does not mark searchable is not offered in search.
      out.add(
          Boolean.TRUE.equals(shadow.getSearchable())
              ? field.withSearchSpec(relocated)
              : field.withUsages(withoutSearch(field.usages()), field.itemPath()));
    }
    return out;
  }

  private static boolean isExtensionStorage(VerticalSchemaField field) {
    return field.getStorage() != null && EXTENSION_STORAGE.equalsIgnoreCase(field.getStorage().trim());
  }

  private static Set<FieldUsage> withoutSearch(Set<FieldUsage> usages) {
    Set<FieldUsage> out = new java.util.HashSet<>(usages);
    out.remove(FieldUsage.SEARCH);
    return out;
  }

  /** Default sort for search: the schema's first default sort key when it names a catalog field. */
  public static Optional<String> schemaDefaultSort(VerticalSchema schema, FieldCatalog catalog) {
    if (schema == null || schema.getEntities() == null) {
      return Optional.empty();
    }
    VerticalEntitySchema inventory = schema.getEntities().get(INVENTORY_ENTITY);
    if (inventory == null || inventory.getSearch() == null || inventory.getSearch().getDefaultSort() == null) {
      return Optional.empty();
    }
    for (var sortKey : inventory.getSearch().getDefaultSort()) {
      if (sortKey == null || !StringUtils.hasText(sortKey.getField())) {
        continue;
      }
      String key = sortKey.getField().trim();
      Optional<PrintableField> match =
          catalog.findForUsage(key, FieldUsage.SEARCH)
              .or(() -> catalog.findForUsage(LabelFieldKeys.verticalKey(key), FieldUsage.SEARCH));
      if (match.isPresent() && match.get().searchSpec().sortable()) {
        String dir = "desc".equalsIgnoreCase(sortKey.getDirection()) ? "desc" : "asc";
        return Optional.of(match.get().fieldKey() + ":" + dir);
      }
    }
    return Optional.empty();
  }

  // ---- helpers -------------------------------------------------------------------------------

  /** Removes later duplicates by {@code fieldKey}, preserving the order of first occurrence. */
  private static List<PrintableField> dedupByFieldKey(List<PrintableField> fields) {
    Map<String, PrintableField> byKey = new LinkedHashMap<>();
    for (PrintableField field : fields) {
      byKey.putIfAbsent(field.fieldKey(), field);
    }
    return List.copyOf(byKey.values());
  }
}
