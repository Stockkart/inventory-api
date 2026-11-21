package com.inventory.analytics.utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.inventory.product.domain.model.Inventory;
import com.inventory.product.domain.model.catalog.InventoryProductReadHandler;
import com.inventory.product.domain.model.pricing.InventoryPricingReadHandler;
import com.inventory.product.domain.repository.InventoryRepository;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import org.bson.Document;
import org.bson.types.Decimal128;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.repository.support.MongoRepositoryFactory;

/**
 * The analytics tabs' batched lot read against a real MongoDB: the same lots in the same order as
 * {@code findByShopId}, every stored field the tabs use (and the ids hydration reads) equal, the
 * rest left out, and product + pricing hydration run on each batch before it is handed over.
 *
 * <pre>
 *   mvn -pl core/analytics -am test -Dtest='ShopLotBatchesIT' -Dsurefire.failIfNoSpecifiedTests=false \
 *       -Dstockkart.test.mongo.uri=mongodb://localhost:27018/stockkart_test
 * </pre>
 */
@EnabledIfSystemProperty(named = "stockkart.test.mongo.uri", matches = ".+")
class ShopLotBatchesIT {

  private static final String SHOP = "batch-shop";
  private static int LOTS; // the shop's lots: two batches, the last one partial

  private static MongoClient client;
  private static MongoTemplate template;
  private static InventoryRepository repository;

  @BeforeAll
  static void seed() {
    String uri = System.getProperty("stockkart.test.mongo.uri");
    client = MongoClients.create(uri);
    String db = uri.substring(uri.lastIndexOf('/') + 1).split("\\?")[0] + "_batches";
    template = new MongoTemplate(client, db);
    template.getDb().drop();
    template.getCollection("inventory").createIndex(new Document("shopId", 1).append("createdAt", -1));
    repository = new MongoRepositoryFactory(template).getRepository(InventoryRepository.class);
    List<Document> docs = new ArrayList<>();
    for (int i = 0; i < 2_645; i++) {
      LOTS += i % 9 == 0 ? 0 : 1;
      Document lot = new Document("_id", new ObjectId())
          .append("shopId", i % 9 == 0 ? "other-shop" : SHOP)
          .append("lotId", "L" + i)
          .append("productId", "p" + (i % 50))
          .append("vendorId", i % 4 == 0 ? null : "v" + (i % 6))
          .append("location", "Rack " + (i % 30))
          .append("receivedCount", new Decimal128(10 + i % 40))
          .append("soldCount", new Decimal128(i % 7))
          .append("currentCount", new Decimal128(10 + i % 40 - i % 7))
          .append("receivedDate", new Date(1_770_000_000_000L + i * 3_600_000L))
          .append("expiryDate", new Date(1_800_000_000_000L))
          .append("batchNo", "B" + i)
          .append("createdAt", new Date(1_770_000_000_000L + (i % 97) * 60_000L));
      if (i % 3 != 0) { // some lots only have display counts
        lot.append("receivedBaseCount", 100 + i).append("soldBaseCount", i % 7).append("currentBaseCount", 93 + i);
      }
      if (i % 2 == 0) {
        lot.append("pricingId", "pr" + i).append("billingMode", "REGULAR");
      }
      docs.add(lot);
    }
    template.getCollection("inventory").insertMany(docs);
  }

  @AfterAll
  static void tearDown() {
    if (template != null) {
      template.getDb().drop();
    }
    if (client != null) {
      client.close();
    }
  }

  @Test
  void sameLotsInTheSameOrderWithTheFieldsTheTabsUse() {
    InventoryProductReadHandler products = mock(InventoryProductReadHandler.class);
    InventoryPricingReadHandler pricing = mock(InventoryPricingReadHandler.class);
    List<Integer> sizes = new ArrayList<>();
    List<Inventory> streamed = new ArrayList<>();
    new ShopLotBatches(template, products, pricing).forEach(SHOP, batch -> {
      sizes.add(batch.size());
      streamed.addAll(batch);
    });

    List<Inventory> full = repository.findByShopId(SHOP);
    assertThat(full).hasSize(LOTS);
    assertThat(sizes).containsExactly(2000, LOTS - 2000);
    assertThat(streamed)
        .usingRecursiveFieldByFieldElementComparatorOnFields(
            "id", "lotId", "productId", "pricingId", "billingMode", "shopId", "vendorId", "location",
            "receivedCount", "soldCount", "currentCount", "receivedBaseCount", "soldBaseCount",
            "currentBaseCount", "receivedDate")
        .containsExactlyElementsOf(full); // same order too
    assertThat(streamed).allSatisfy(lot -> {
      assertThat(lot.getBatchNo()).isNull();
      assertThat(lot.getExpiryDate()).isNull();
      assertThat(lot.getCreatedAt()).isNull();
    });
    verify(products, times(2)).enrich(anyList());
    verify(pricing, times(2)).enrich(anyList());
  }

  @Test
  void eachBatchIsHydratedBeforeItIsHandedOver() {
    InventoryProductReadHandler products = mock(InventoryProductReadHandler.class);
    InventoryPricingReadHandler pricing = mock(InventoryPricingReadHandler.class);
    doAnswer(inv -> {
      List<Inventory> batch = inv.getArgument(0);
      batch.forEach(l -> l.setName("name of " + l.getProductId()));
      return null;
    }).when(products).enrich(anyList());
    doAnswer(inv -> {
      List<Inventory> batch = inv.getArgument(0);
      batch.forEach(l -> l.setCostPrice(BigDecimal.TEN));
      return null;
    }).when(pricing).enrich(anyList());

    List<Inventory> seen = new ArrayList<>();
    new ShopLotBatches(template, products, pricing).forEach(SHOP, seen::addAll);

    assertThat(seen).hasSize(LOTS).allSatisfy(lot -> {
      assertThat(lot.getName()).isEqualTo("name of " + lot.getProductId());
      assertThat(lot.getCostPrice()).isEqualTo(BigDecimal.TEN);
    });
  }

  @Test
  void aShopWithNoLotsCallsNothing() {
    List<List<Inventory>> batches = new ArrayList<>();
    new ShopLotBatches(template, mock(InventoryProductReadHandler.class), mock(InventoryPricingReadHandler.class))
        .forEach("no-such-shop", batches::add);
    assertThat(batches).isEmpty();
  }
}
