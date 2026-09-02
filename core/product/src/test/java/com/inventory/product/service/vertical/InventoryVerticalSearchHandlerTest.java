package com.inventory.product.service.vertical;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.inventory.pluginengine.InventorySearchProvider;
import com.inventory.pluginengine.InventorySearchResult;
import com.inventory.pluginengine.PluginRegistry;
import com.inventory.pluginengine.VerticalPlugin;
import com.inventory.product.domain.model.Inventory;
import com.inventory.product.domain.model.Shop;
import com.inventory.product.domain.repository.InventoryRepository;
import com.inventory.product.domain.repository.ShopRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;

@ExtendWith(MockitoExtension.class)
class InventoryVerticalSearchHandlerTest {

  private static final String SHOP_ID = "shop-1";

  @Mock private ShopRepository shopRepository;
  @Mock private PluginRegistry pluginRegistry;
  @Mock private SchemaLoader schemaLoader;
  @Mock private InventoryRepository inventoryRepository;
  @Mock private MongoTemplate mongoTemplate;
  @Mock private InventoryVerticalExtensionHandler extensionHandler;
  @Mock private VerticalPlugin verticalPlugin;
  @Mock private InventorySearchProvider searchProvider;

  private InventoryVerticalSearchHandler handler;

  @BeforeEach
  void setUp() {
    handler =
        new InventoryVerticalSearchHandler(
            shopRepository,
            pluginRegistry,
            schemaLoader,
            inventoryRepository,
            mongoTemplate,
            extensionHandler);
  }

  @Test
  void textSearch_doesNotRequireExtensionRow_evenWhenProviderExists() {
    Shop shop = medicalShop();
    when(shopRepository.findById(SHOP_ID)).thenReturn(Optional.of(shop));
    when(pluginRegistry.find("medical")).thenReturn(Optional.of(verticalPlugin));
    when(verticalPlugin.getSearchProvider()).thenReturn(Optional.of(searchProvider));
    when(searchProvider.findInventoryIdsByBatchPrefix(eq(SHOP_ID), any())).thenReturn(List.of());
    when(mongoTemplate.find(any(Query.class), eq(com.inventory.product.domain.model.Product.class)))
        .thenReturn(List.of());
    when(mongoTemplate.find(any(Query.class), eq(Inventory.class)))
        .thenReturn(List.of(inventory("lot-a"), inventory("lot-b")));
    when(inventoryRepository.findByIdIn(List.of("lot-a", "lot-b")))
        .thenReturn(List.of(inventory("lot-a"), inventory("lot-b")));

    var page =
        handler.searchPage(SHOP_ID, "baby", Map.of(), null, 50, null, 0, false);

    assertEquals(2, page.items().size());
    verify(searchProvider, never()).search(eq(SHOP_ID), any());
  }

  @Test
  void textSearch_sortsByExtensionFieldWithNullsLast() {
    Shop shop = medicalShop();
    when(shopRepository.findById(SHOP_ID)).thenReturn(Optional.of(shop));
    when(pluginRegistry.find("medical")).thenReturn(Optional.of(verticalPlugin));
    when(verticalPlugin.getSearchProvider()).thenReturn(Optional.of(searchProvider));
    when(searchProvider.findInventoryIdsByBatchPrefix(eq(SHOP_ID), any())).thenReturn(List.of());
    when(mongoTemplate.find(any(Query.class), eq(com.inventory.product.domain.model.Product.class)))
        .thenReturn(List.of());
    Inventory withExpiry = inventory("with-expiry");
    Inventory withoutExpiry = inventory("no-expiry");
    when(mongoTemplate.find(any(Query.class), eq(Inventory.class)))
        .thenReturn(List.of(withoutExpiry, withExpiry));
    when(inventoryRepository.findByIdIn(List.of("no-expiry", "with-expiry")))
        .thenReturn(List.of(withoutExpiry, withExpiry));
    Map<String, Map<String, Object>> extensionFields = new LinkedHashMap<>();
    extensionFields.put(
        "with-expiry", Map.of("expiryDate", Instant.parse("2027-01-01T00:00:00Z")));
    extensionFields.put("no-expiry", Map.of());
    when(extensionHandler.loadExtensionFieldsBatch(eq(SHOP_ID), anyList()))
        .thenReturn(extensionFields);

    var page =
        handler.searchPage(
            SHOP_ID, "baby", Map.of(), "expiryDate:asc", 50, null, 0, false);

    assertEquals(List.of("with-expiry", "no-expiry"), page.items().stream().map(Inventory::getId).toList());
  }

  @Test
  void filterOnlySearch_stillUsesExtensionProvider() {
    Shop shop = medicalShop();
    when(shopRepository.findById(SHOP_ID)).thenReturn(Optional.of(shop));
    when(pluginRegistry.find("medical")).thenReturn(Optional.of(verticalPlugin));
    when(verticalPlugin.getSearchProvider()).thenReturn(Optional.of(searchProvider));
    when(schemaLoader.load("medical", "1.0.0")).thenReturn(medicalSchema());
    when(searchProvider.search(eq(SHOP_ID), any()))
        .thenReturn(
            InventorySearchResult.builder().inventoryIds(List.of("lot-filtered")).build());
    when(inventoryRepository.findByIdIn(List.of("lot-filtered")))
        .thenReturn(List.of(inventory("lot-filtered")));

    var page =
        handler.searchPage(
            SHOP_ID, null, Map.of("batchNo", "ABC"), "expiryDate:asc", 10, null, 0, false);

    assertEquals(1, page.items().size());
    assertEquals("lot-filtered", page.items().get(0).getId());
    verify(searchProvider).search(eq(SHOP_ID), any());
  }

  @Test
  void textSearch_paginatesWithTotalMatched() {
    Shop shop = medicalShop();
    when(shopRepository.findById(SHOP_ID)).thenReturn(Optional.of(shop));
    when(pluginRegistry.find("medical")).thenReturn(Optional.of(verticalPlugin));
    when(verticalPlugin.getSearchProvider()).thenReturn(Optional.of(searchProvider));
    when(searchProvider.findInventoryIdsByBatchPrefix(eq(SHOP_ID), any())).thenReturn(List.of());
    when(mongoTemplate.find(any(Query.class), eq(com.inventory.product.domain.model.Product.class)))
        .thenReturn(List.of());
    List<Inventory> lots = new ArrayList<>();
    for (int i = 0; i < 12; i++) {
      lots.add(inventory("lot-" + i));
    }
    when(mongoTemplate.find(any(Query.class), eq(Inventory.class))).thenReturn(lots);
    when(inventoryRepository.findByIdIn(anyList())).thenAnswer(
        invocation -> {
          @SuppressWarnings("unchecked")
          List<String> ids = invocation.getArgument(0);
          return ids.stream().map(InventoryVerticalSearchHandlerTest::inventory).toList();
        });

    var page =
        handler.searchPage(SHOP_ID, "baby", Map.of(), null, 10, null, 0, false);

    assertEquals(10, page.items().size());
    assertEquals(12, page.totalMatched());

    var page2 =
        handler.searchPage(SHOP_ID, "baby", Map.of(), null, 10, null, 10, false);

    assertEquals(2, page2.items().size());
    assertEquals(12, page2.totalMatched());
  }

  @Test
  void textSearchWithExplicitFilters_usesExtensionProvider() {
    Shop shop = medicalShop();
    when(shopRepository.findById(SHOP_ID)).thenReturn(Optional.of(shop));
    when(pluginRegistry.find("medical")).thenReturn(Optional.of(verticalPlugin));
    when(verticalPlugin.getSearchProvider()).thenReturn(Optional.of(searchProvider));
    when(searchProvider.findInventoryIdsByBatchPrefix(eq(SHOP_ID), any())).thenReturn(List.of());
    when(mongoTemplate.find(any(Query.class), eq(com.inventory.product.domain.model.Product.class)))
        .thenReturn(List.of());
    when(mongoTemplate.find(any(Query.class), eq(Inventory.class)))
        .thenReturn(List.of(inventory("lot-a")));
    when(schemaLoader.load("medical", "1.0.0")).thenReturn(medicalSchema());
    when(searchProvider.search(eq(SHOP_ID), any()))
        .thenReturn(InventorySearchResult.builder().inventoryIds(List.of("lot-a")).build());
    when(inventoryRepository.findByIdIn(List.of("lot-a"))).thenReturn(List.of(inventory("lot-a")));

    var page =
        handler.searchPage(
            SHOP_ID, "baby", Map.of("batchNo", "ABC"), null, 50, null, 0, false);

    assertEquals(1, page.items().size());
    verify(searchProvider).search(eq(SHOP_ID), any());
  }

  private static Shop medicalShop() {
    Shop shop = new Shop();
    shop.setShopId(SHOP_ID);
    shop.setVerticalId("medical");
    shop.setPluginVersion("1.0.0");
    return shop;
  }

  private static com.inventory.pluginengine.schema.VerticalSchema medicalSchema() {
    var schema = new com.inventory.pluginengine.schema.VerticalSchema();
    var batch = new com.inventory.pluginengine.schema.VerticalSchemaField();
    batch.setKey("batchNo");
    batch.setSearchable(true);
    var expiry = new com.inventory.pluginengine.schema.VerticalSchemaField();
    expiry.setKey("expiryDate");
    expiry.setSearchable(true);
    var entity = new com.inventory.pluginengine.schema.VerticalEntitySchema();
    entity.setFields(List.of(batch, expiry));
    schema.setEntities(Map.of("inventory", entity));
    return schema;
  }

  private static Inventory inventory(String id) {
    Inventory inventory = new Inventory();
    inventory.setId(id);
    inventory.setShopId(SHOP_ID);
    inventory.setCurrentCount(BigDecimal.TEN);
    return inventory;
  }
}
