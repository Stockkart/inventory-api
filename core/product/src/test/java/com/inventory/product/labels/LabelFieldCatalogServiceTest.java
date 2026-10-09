package com.inventory.product.labels;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.inventory.common.exception.ResourceNotFoundException;
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
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class LabelFieldCatalogServiceTest {

  private static final String SHOP_ID = "shop-1";

  @Mock private ShopRepository shopRepository;
  @Mock private PricingRepository pricingRepository;
  @Mock private SchemaLoader schemaLoader;

  private LabelFieldCatalogService service;

  @BeforeEach
  void setUp() {
    service =
        new LabelFieldCatalogService(
            shopRepository, pricingRepository, schemaLoader, new VerticalValueTypeMapper());
  }

  // ---- shop loading --------------------------------------------------------------------------

  @Test
  void catalogByShopIdThrowsWhenShopMissing() {
    when(shopRepository.findById(SHOP_ID)).thenReturn(Optional.empty());

    assertThrows(ResourceNotFoundException.class, () -> service.catalog(SHOP_ID));
    verify(pricingRepository, never()).findDistinctRateNamesByShopId(anyString());
  }

  @Test
  void catalogByShopIdLoadsShopAndDelegates() {
    Shop shop = shop(ShopType.RETAILER, null, null);
    when(shopRepository.findById(SHOP_ID)).thenReturn(Optional.of(shop));
    when(pricingRepository.findDistinctRateNamesByShopId(SHOP_ID)).thenReturn(List.of());

    FieldCatalog catalog = service.catalog(SHOP_ID);

    assertEquals(ShopType.RETAILER, catalog.effectiveShopType());
    assertTrue(catalog.find(LabelFieldKeys.PRODUCT_NAME).isPresent());
  }

  // ---- static groups, order, sticker sizes ---------------------------------------------------

  @Test
  void assemblesStaticGroupsInCatalogOrderWithStickerSizes() {
    Shop shop = shop(ShopType.RETAILER, null, null);
    when(pricingRepository.findDistinctRateNamesByShopId(SHOP_ID)).thenReturn(List.of());

    FieldCatalog catalog = service.catalog(shop);

    List<String> expectedKeys = new ArrayList<>();
    LabelLayoutDefaults.coreFields().forEach(f -> expectedKeys.add(f.fieldKey()));
    LabelLayoutDefaults.pricingFields().forEach(f -> expectedKeys.add(f.fieldKey()));
    LabelLayoutDefaults.lotFields().forEach(f -> expectedKeys.add(f.fieldKey()));
    LabelLayoutDefaults.shopFields().forEach(f -> expectedKeys.add(f.fieldKey()));
    assertEquals(expectedKeys, keys(catalog));

    assertEquals(LabelLayoutDefaults.STICKER_SIZES, catalog.stickerSizes());
    assertFalse(catalog.verticalSchemaLoaded());
    assertTrue(catalog.inventorySchemaFields().isEmpty());
    assertTrue(catalog.productSchemaFields().isEmpty());
    verify(schemaLoader, never()).load(anyString(), any());
  }

  // ---- card view (configurable-product-card Req 1.2–1.7) -------------------------------------

  @Test
  void cardViewInterleavesCardOnlyFieldsAfterEachGroupAndExcludesShopFields() {
    Shop shop = shop(ShopType.RETAILER, null, null);
    when(pricingRepository.findDistinctRateNamesByShopId(SHOP_ID)).thenReturn(List.of("Retail"));

    FieldCatalog catalog = service.catalog(shop);

    List<String> expected = new ArrayList<>();
    LabelLayoutDefaults.coreFields().forEach(f -> expected.add(f.fieldKey()));
    LabelLayoutDefaults.cardProductFields().forEach(f -> expected.add(f.fieldKey()));
    LabelLayoutDefaults.pricingFields().forEach(f -> expected.add(f.fieldKey()));
    expected.add(LabelFieldKeys.pricingRateKey("Retail"));
    LabelLayoutDefaults.cardPricingFields().forEach(f -> expected.add(f.fieldKey()));
    LabelLayoutDefaults.lotFields().forEach(f -> expected.add(f.fieldKey()));
    LabelLayoutDefaults.cardLotFields().forEach(f -> expected.add(f.fieldKey()));
    assertEquals(expected, cardKeys(catalog));

    // every card field knows where its value lives; shop fields are sticker-only
    catalog.forUsage(FieldUsage.CARD).forEach(f -> assertTrue(f.itemPath() != null, f.fieldKey()));
    assertTrue(cardKeys(catalog).stream().noneMatch(k -> k.startsWith("shop")));
    LabelLayoutDefaults.shopFields()
        .forEach(f -> assertFalse(f.usableFor(FieldUsage.CARD), f.fieldKey()));
  }

  @Test
  void cardViewMarksShopInternalFields() {
    Shop shop = shop(ShopType.RETAILER, null, null);
    when(pricingRepository.findDistinctRateNamesByShopId(SHOP_ID)).thenReturn(List.of());

    FieldCatalog catalog = service.catalog(shop);

    Set<String> internal =
        catalog.forUsage(FieldUsage.CARD).stream()
            .filter(f -> f.sensitivity() == Sensitivity.SHOP_INTERNAL)
            .map(PrintableField::fieldKey)
            .collect(java.util.stream.Collectors.toSet());
    assertEquals(
        Set.of(
            LabelFieldKeys.COST_PRICE,
            LabelFieldKeys.PURCHASE_ADDITIONAL_DISCOUNT,
            LabelFieldKeys.PURCHASE_SCHEME,
            LabelFieldKeys.EFFECTIVE_COST_PRICE),
        internal);
  }

  @Test
  void verticalFieldsAreUsableOnCardsViaVerticalFieldsPath() {
    Shop shop = shop(ShopType.RETAILER, "sports", "1.0.0");
    when(pricingRepository.findDistinctRateNamesByShopId(SHOP_ID)).thenReturn(List.of());
    when(schemaLoader.load("sports", "1.0.0"))
        .thenReturn(
            schema(
                Map.of(
                    LabelFieldCatalogService.INVENTORY_ENTITY,
                    entity(field("brand", "brandName", "Brand", "string")))));

    PrintableField brand = service.catalog(shop).findForUsage("vertical.brand", FieldUsage.CARD).orElseThrow();

    assertEquals("verticalFields.brandName", brand.itemPath());
    assertTrue(brand.usableFor(FieldUsage.LABEL));
    assertEquals(Sensitivity.PUBLIC, brand.sensitivity());
  }

  @Test
  void nullShopTypeNormalizesToRetailer() {
    Shop shop = shop(null, null, null);
    when(pricingRepository.findDistinctRateNamesByShopId(SHOP_ID)).thenReturn(List.of());

    assertEquals(ShopType.RETAILER, service.catalog(shop).effectiveShopType());
  }

  @Test
  void keepsDistributorShopType() {
    Shop shop = shop(ShopType.DISTRIBUTOR, null, null);
    when(pricingRepository.findDistinctRateNamesByShopId(SHOP_ID)).thenReturn(List.of());

    assertEquals(ShopType.DISTRIBUTOR, service.catalog(shop).effectiveShopType());
  }

  // ---- shop-type availability (Req 1.6) ------------------------------------------------------

  @ParameterizedTest
  @EnumSource(ShopType.class)
  void coreLotShopAndUnrestrictedPricingFieldsAvailableForEveryShopType(ShopType shopType) {
    Shop shop = shop(shopType, null, null);
    when(pricingRepository.findDistinctRateNamesByShopId(SHOP_ID)).thenReturn(List.of());

    FieldCatalog catalog = service.catalog(shop);

    for (PrintableField f : LabelLayoutDefaults.coreFields()) {
      assertTrue(catalog.find(f.fieldKey()).orElseThrow().isAvailableFor(shopType), f.fieldKey());
    }
    for (PrintableField f : LabelLayoutDefaults.lotFields()) {
      assertTrue(catalog.find(f.fieldKey()).orElseThrow().isAvailableFor(shopType), f.fieldKey());
    }
    for (PrintableField f : LabelLayoutDefaults.shopFields()) {
      assertTrue(catalog.find(f.fieldKey()).orElseThrow().isAvailableFor(shopType), f.fieldKey());
    }
    assertTrue(catalog.find(LabelFieldKeys.MRP).orElseThrow().isAvailableFor(shopType));
    assertTrue(catalog.find(LabelFieldKeys.SELLING_PRICE).orElseThrow().isAvailableFor(shopType));
  }

  @ParameterizedTest
  @EnumSource(ShopType.class)
  void tradePricingFieldsAvailableOnlyForDistributorAndWholesaler(ShopType shopType) {
    Shop shop = shop(shopType, null, null);
    when(pricingRepository.findDistinctRateNamesByShopId(SHOP_ID)).thenReturn(List.of("Retail"));

    FieldCatalog catalog = service.catalog(shop);

    boolean trade = shopType == ShopType.DISTRIBUTOR || shopType == ShopType.WHOLESALER;
    List<String> restricted =
        List.of(
            LabelFieldKeys.PTR,
            LabelFieldKeys.COST_PRICE,
            LabelFieldKeys.SALE_SCHEME,
            LabelFieldKeys.GST_RATE,
            LabelFieldKeys.pricingRateKey("Retail"));
    for (String key : restricted) {
      PrintableField f = catalog.find(key).orElseThrow();
      assertEquals(trade, f.isAvailableFor(shopType), key + " availability for " + shopType);
      assertEquals(
          Set.of(ShopType.DISTRIBUTOR, ShopType.WHOLESALER), f.availableForShopTypes(), key);
    }
  }

  @Test
  void restrictedFieldsStillListedInRetailerCatalog() {
    Shop shop = shop(ShopType.RETAILER, null, null);
    when(pricingRepository.findDistinctRateNamesByShopId(SHOP_ID)).thenReturn(List.of("Trade"));

    List<String> keys = keys(service.catalog(shop));

    // the catalog carries the full set; availability is expressed per field, not by omission
    assertTrue(keys.contains(LabelFieldKeys.PTR));
    assertTrue(keys.contains(LabelFieldKeys.pricingRateKey("Trade")));
  }

  // ---- sticker sizes (Req 3.5) ---------------------------------------------------------------

  @ParameterizedTest
  @EnumSource(ShopType.class)
  void everyResponseCarriesAllStickerSizePresetsWithMaxLines(ShopType shopType) {
    Shop shop = shop(shopType, null, null);
    when(pricingRepository.findDistinctRateNamesByShopId(SHOP_ID)).thenReturn(List.of());

    List<StickerSizeSpec> sizes = service.catalog(shop).stickerSizes();

    assertEquals(LabelLayoutDefaults.STICKER_SIZES, sizes);
    assertEquals(
        List.of(
            new StickerSizeSpec("50x25", 50, 25, 3),
            new StickerSizeSpec("38x25", 38, 25, 2),
            new StickerSizeSpec("38x38", 38, 38, 4),
            new StickerSizeSpec("100x50", 100, 50, 6)),
        sizes);
  }

  @Test
  void stickerSizesPresentWhenVerticalSchemaLoaded() {
    Shop shop = shop(ShopType.RETAILER, "medical", "1.0.0");
    when(pricingRepository.findDistinctRateNamesByShopId(SHOP_ID)).thenReturn(List.of());
    when(schemaLoader.load("medical", "1.0.0"))
        .thenReturn(
            schema(
                Map.of(
                    LabelFieldCatalogService.INVENTORY_ENTITY,
                    entity(field("rack", null, "Rack", "string")))));

    assertEquals(LabelLayoutDefaults.STICKER_SIZES, service.catalog(shop).stickerSizes());
  }

  // ---- schema changes reflected on next call (Req 1.4) ---------------------------------------

  @Test
  void newSchemaFromLoaderIsReflectedOnNextCallWithoutRestart() {
    Shop shop = shop(ShopType.RETAILER, "medical", "1.0.0");
    when(pricingRepository.findDistinctRateNamesByShopId(SHOP_ID)).thenReturn(List.of());
    VerticalSchema schemaA =
        schema(
            Map.of(
                LabelFieldCatalogService.INVENTORY_ENTITY,
                entity(
                    field("rack", null, "Rack", "string"),
                    field("schedule", null, "Schedule", "enum"))));
    VerticalSchema schemaB =
        schema(
            Map.of(
                LabelFieldCatalogService.INVENTORY_ENTITY,
                entity(
                    field("rack", null, "Shelf location", "string"),
                    field("mfgDate", null, "Mfg. date", "date"))));
    when(schemaLoader.load("medical", "1.0.0")).thenReturn(schemaA, schemaB);

    FieldCatalog first = service.catalog(shop);
    FieldCatalog second = service.catalog(shop);

    List<String> firstVertical = keys(first).stream().filter(LabelFieldKeys::isVerticalKey).toList();
    List<String> secondVertical =
        keys(second).stream().filter(LabelFieldKeys::isVerticalKey).toList();
    assertEquals(List.of("vertical.rack", "vertical.schedule"), firstVertical);
    assertEquals(List.of("vertical.rack", "vertical.mfgDate"), secondVertical);

    assertTrue(second.find("vertical.mfgDate").isPresent(), "added field appears");
    assertFalse(second.find("vertical.schedule").isPresent(), "removed field disappears");
    assertEquals("Rack", first.find("vertical.rack").orElseThrow().label());
    assertEquals(
        "Shelf location",
        second.find("vertical.rack").orElseThrow().label(),
        "relabeled field carries the new label");
    verify(schemaLoader, times(2)).load("medical", "1.0.0");
  }

  @Test
  void loaderRecoveryAfterFailureIsReflectedOnNextCall() {
    Shop shop = shop(ShopType.RETAILER, "medical", "1.0.0");
    when(pricingRepository.findDistinctRateNamesByShopId(SHOP_ID)).thenReturn(List.of());
    when(schemaLoader.load("medical", "1.0.0"))
        .thenThrow(new IllegalStateException("plugin unavailable"))
        .thenReturn(
            schema(
                Map.of(
                    LabelFieldCatalogService.INVENTORY_ENTITY,
                    entity(field("rack", null, "Rack", "string")))));

    FieldCatalog first = service.catalog(shop);
    FieldCatalog second = service.catalog(shop);

    assertFalse(first.verticalSchemaLoaded());
    assertTrue(keys(first).stream().noneMatch(LabelFieldKeys::isVerticalKey));
    assertTrue(second.verticalSchemaLoaded());
    assertTrue(second.find("vertical.rack").isPresent());
  }

  // ---- named rates ---------------------------------------------------------------------------

  @Test
  void appendsNamedRateFieldsAfterStaticPricingAndBeforeLot() {
    Shop shop = shop(ShopType.WHOLESALER, null, null);
    when(pricingRepository.findDistinctRateNamesByShopId(SHOP_ID))
        .thenReturn(List.of("Retail", "Wholesale"));

    List<String> keys = keys(service.catalog(shop));

    int gstIdx = keys.indexOf(LabelFieldKeys.GST_RATE);
    int retailIdx = keys.indexOf(LabelFieldKeys.pricingRateKey("Retail"));
    int wholesaleIdx = keys.indexOf(LabelFieldKeys.pricingRateKey("Wholesale"));
    int batchIdx = keys.indexOf(LabelFieldKeys.BATCH_NO);
    assertTrue(gstIdx >= 0 && retailIdx == gstIdx + 1, "Retail rate follows static pricing");
    assertEquals(retailIdx + 1, wholesaleIdx, "rate order preserved");
    assertEquals(wholesaleIdx + 1, batchIdx, "lot group follows named rates");
  }

  // ---- vertical group ------------------------------------------------------------------------

  @Test
  void verticalFieldsPlacedBetweenLotAndShopWithMappedTypesAndApiKey() {
    Shop shop = shop(ShopType.RETAILER, "medical", "1.0.0");
    when(pricingRepository.findDistinctRateNamesByShopId(SHOP_ID)).thenReturn(List.of());

    VerticalSchemaField schedule = field("schedule", null, "Drug schedule", "enum");
    VerticalSchemaField mfgDate = field("mfgDate", "manufacturingDate", "Mfg. date", "date");
    VerticalSchemaField mrpPerStrip = field("mrpPerStrip", null, null, "money");
    VerticalSchemaField discount = field("discount", null, "Discount", "percent");
    VerticalSchemaField qtyPerPack = field("qtyPerPack", null, "Qty/pack", "number");
    when(schemaLoader.load("medical", "1.0.0"))
        .thenReturn(
            schema(
                Map.of(
                    LabelFieldCatalogService.INVENTORY_ENTITY,
                    entity(schedule, mfgDate, mrpPerStrip, discount, qtyPerPack))));

    FieldCatalog catalog = service.catalog(shop);

    assertTrue(catalog.verticalSchemaLoaded());
    List<String> keys = keys(catalog);
    int receivedIdx = keys.indexOf(LabelFieldKeys.RECEIVED_DATE);
    int shopNameIdx = keys.indexOf(LabelFieldKeys.SHOP_NAME);
    List<String> verticalKeys = keys.subList(receivedIdx + 1, shopNameIdx);
    assertEquals(
        List.of(
            "vertical.schedule",
            "vertical.mfgDate",
            "vertical.mrpPerStrip",
            "vertical.discount",
            "vertical.qtyPerPack"),
        verticalKeys);

    PrintableField sched = catalog.find("vertical.schedule").orElseThrow();
    assertEquals("Drug schedule", sched.label());
    assertEquals(SourceGroup.VERTICAL, sched.sourceGroup());
    assertEquals(ValueType.TEXT, sched.valueType());
    assertEquals("schedule", sched.schemaApiKey());
    assertEquals(
        Set.of(ShopType.RETAILER, ShopType.DISTRIBUTOR, ShopType.WHOLESALER),
        sched.availableForShopTypes());

    PrintableField mfg = catalog.find("vertical.mfgDate").orElseThrow();
    assertEquals(ValueType.DATE, mfg.valueType());
    assertEquals("manufacturingDate", mfg.schemaApiKey(), "apiKey preferred over key");

    assertEquals("mrpPerStrip", catalog.find("vertical.mrpPerStrip").orElseThrow().label(),
        "blank label falls back to key");
    assertEquals(ValueType.CURRENCY, catalog.find("vertical.mrpPerStrip").orElseThrow().valueType());
    assertEquals(ValueType.PERCENTAGE, catalog.find("vertical.discount").orElseThrow().valueType());
    assertEquals(ValueType.NUMBER, catalog.find("vertical.qtyPerPack").orElseThrow().valueType());

    assertEquals(5, catalog.inventorySchemaFields().size());
    assertTrue(catalog.productSchemaFields().isEmpty());
  }

  @Test
  void skipsExcludedTypesAndCoreLotBackedFields() {
    Shop shop = shop(ShopType.RETAILER, "medical", "1.0.0");
    when(pricingRepository.findDistinctRateNamesByShopId(SHOP_ID)).thenReturn(List.of());
    when(schemaLoader.load("medical", "1.0.0"))
        .thenReturn(
            schema(
                Map.of(
                    LabelFieldCatalogService.INVENTORY_ENTITY,
                    entity(
                        field("batchNo", null, "Batch", "string"),
                        field("expiryDate", null, "Expiry", "date"),
                        field("lotName", "name", "Name", "string"),
                        field("BatchNo", null, "Batch (caps)", "string"),
                        field("isColdChain", null, "Cold chain", "boolean"),
                        field("tags", null, "Tags", "list"),
                        field("", null, "No key", "string"),
                        field("rack", null, "Rack", "string")))));

    List<String> keys = keys(service.catalog(shop));

    assertFalse(keys.contains("vertical.batchNo"), "batchNo backs a lot field");
    assertFalse(keys.contains("vertical.expiryDate"), "expiryDate backs a lot field");
    assertFalse(keys.contains("vertical.lotName"), "apiKey 'name' backs a core field");
    assertTrue(keys.contains("vertical.BatchNo"), "exclusion is case-sensitive");
    assertFalse(keys.contains("vertical.isColdChain"), "boolean excluded");
    assertFalse(keys.contains("vertical.tags"), "list excluded");
    assertFalse(keys.contains("vertical."), "blank key skipped");
    assertTrue(keys.contains("vertical.rack"));
  }

  @Test
  void truncatesLongLabelsToSixtyChars() {
    Shop shop = shop(ShopType.RETAILER, "medical", "1.0.0");
    when(pricingRepository.findDistinctRateNamesByShopId(SHOP_ID)).thenReturn(List.of());
    String longLabel = "x".repeat(75);
    when(schemaLoader.load("medical", "1.0.0"))
        .thenReturn(
            schema(
                Map.of(
                    LabelFieldCatalogService.INVENTORY_ENTITY,
                    entity(field("longOne", null, longLabel, "string")))));

    PrintableField f = service.catalog(shop).find("vertical.longOne").orElseThrow();

    assertEquals(60, f.label().length());
    assertEquals(longLabel.substring(0, 60), f.label());
  }

  @Test
  void inventoryEntityFieldsPrecedeProductEntityFields() {
    Shop shop = shop(ShopType.RETAILER, "apparel", "2.0.0");
    when(pricingRepository.findDistinctRateNamesByShopId(SHOP_ID)).thenReturn(List.of());
    Map<String, VerticalEntitySchema> entities = new LinkedHashMap<>();
    entities.put(
        LabelFieldCatalogService.PRODUCT_ENTITY, entity(field("brand", null, "Brand", "string")));
    entities.put(
        LabelFieldCatalogService.INVENTORY_ENTITY, entity(field("size", null, "Size", "enum")));
    when(schemaLoader.load("apparel", "2.0.0")).thenReturn(schema(entities));

    FieldCatalog catalog = service.catalog(shop);
    List<String> keys = keys(catalog);

    assertTrue(keys.indexOf("vertical.size") < keys.indexOf("vertical.brand"));
    assertEquals(1, catalog.inventorySchemaFields().size());
    assertEquals(1, catalog.productSchemaFields().size());
  }

  @Test
  void blankVerticalIdYieldsEmptyVerticalSetWithoutLoading() {
    Shop shop = shop(ShopType.RETAILER, "   ", "1.0.0");
    when(pricingRepository.findDistinctRateNamesByShopId(SHOP_ID)).thenReturn(List.of());

    FieldCatalog catalog = service.catalog(shop);

    assertFalse(catalog.verticalSchemaLoaded());
    assertTrue(keys(catalog).stream().noneMatch(LabelFieldKeys::isVerticalKey));
    verify(schemaLoader, never()).load(anyString(), any());
  }

  @Test
  void schemaLoadFailureYieldsEmptyVerticalSetAndFlag() {
    Shop shop = shop(ShopType.RETAILER, "medical", "9.9.9");
    when(pricingRepository.findDistinctRateNamesByShopId(SHOP_ID)).thenReturn(List.of());
    when(schemaLoader.load("medical", "9.9.9"))
        .thenThrow(new ResourceNotFoundException("VerticalSchema", "verticalId", "medical"));

    FieldCatalog catalog = service.catalog(shop);

    assertFalse(catalog.verticalSchemaLoaded());
    assertTrue(keys(catalog).stream().noneMatch(LabelFieldKeys::isVerticalKey));
    // every static group is still present
    assertTrue(catalog.find(LabelFieldKeys.PRODUCT_NAME).isPresent());
    assertTrue(catalog.find(LabelFieldKeys.MRP).isPresent());
    assertTrue(catalog.find(LabelFieldKeys.BATCH_NO).isPresent());
    assertTrue(catalog.find(LabelFieldKeys.SHOP_NAME).isPresent());
    assertEquals(LabelLayoutDefaults.STICKER_SIZES, catalog.stickerSizes());
  }

  @Test
  void schemaWithoutEntitiesIsLoadedButContributesNothing() {
    Shop shop = shop(ShopType.RETAILER, "cafe", "1.0.0");
    when(pricingRepository.findDistinctRateNamesByShopId(SHOP_ID)).thenReturn(List.of());
    when(schemaLoader.load("cafe", "1.0.0")).thenReturn(new VerticalSchema());

    FieldCatalog catalog = service.catalog(shop);

    assertTrue(catalog.verticalSchemaLoaded());
    assertTrue(keys(catalog).stream().noneMatch(LabelFieldKeys::isVerticalKey));
  }

  // ---- dedup ---------------------------------------------------------------------------------

  @Test
  void fieldKeysAreUniqueKeepingFirstOccurrence() {
    Shop shop = shop(ShopType.RETAILER, "medical", "1.0.0");
    when(pricingRepository.findDistinctRateNamesByShopId(SHOP_ID))
        .thenReturn(List.of("Retail", "Retail"));
    when(schemaLoader.load("medical", "1.0.0"))
        .thenReturn(
            schema(
                Map.of(
                    LabelFieldCatalogService.INVENTORY_ENTITY,
                    entity(
                        field("rack", null, "Rack (first)", "string"),
                        field("rack", null, "Rack (second)", "string")))));

    FieldCatalog catalog = service.catalog(shop);
    List<String> keys = keys(catalog);

    assertEquals(new HashSet<>(keys).size(), keys.size(), "no duplicate field keys");
    assertEquals("Rack (first)", catalog.find("vertical.rack").orElseThrow().label());
    assertEquals(
        1, keys.stream().filter(k -> k.equals(LabelFieldKeys.pricingRateKey("Retail"))).count());
  }

  // ---- helpers -------------------------------------------------------------------------------

  private static Shop shop(ShopType type, String verticalId, String pluginVersion) {
    Shop shop = new Shop();
    shop.setShopId(SHOP_ID);
    shop.setShopType(type);
    shop.setVerticalId(verticalId);
    shop.setPluginVersion(pluginVersion);
    return shop;
  }

  private static VerticalSchemaField field(String key, String apiKey, String label, String type) {
    VerticalSchemaField f = new VerticalSchemaField();
    f.setKey(key);
    f.setApiKey(apiKey);
    f.setLabel(label);
    f.setType(type);
    return f;
  }

  private static VerticalEntitySchema entity(VerticalSchemaField... fields) {
    VerticalEntitySchema e = new VerticalEntitySchema();
    e.setFields(List.of(fields));
    return e;
  }

  private static VerticalSchema schema(Map<String, VerticalEntitySchema> entities) {
    VerticalSchema s = new VerticalSchema();
    s.setEntities(entities);
    return s;
  }

  /** Keys of the sticker view of the catalog — what the label endpoint and validator see. */
  private static List<String> keys(FieldCatalog catalog) {
    return catalog.forUsage(FieldUsage.LABEL).stream().map(PrintableField::fieldKey).toList();
  }

  /** Keys of the card view of the catalog. */
  private static List<String> cardKeys(FieldCatalog catalog) {
    return catalog.forUsage(FieldUsage.CARD).stream().map(PrintableField::fieldKey).toList();
  }
}
