package com.inventory.product.domain.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventory.product.domain.model.Inventory;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import java.util.Comparator;
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
 * The analytics lot read against a real MongoDB: the same lots as {@code findByShopId}, every
 * stored field the Inventory and Vendors tabs use (and the ids the read aspects hydrate from)
 * equal, and the rest of the document left out.
 *
 * <pre>
 *   mvn -pl core/product -am test -Dtest='InventoryAnalyticsLotsIT' -Dsurefire.failIfNoSpecifiedTests=false \
 *       -Dstockkart.test.mongo.uri=mongodb://localhost:27018/stockkart_test
 * </pre>
 */
@EnabledIfSystemProperty(named = "stockkart.test.mongo.uri", matches = ".+")
class InventoryAnalyticsLotsIT {

  private static final String SHOP = "lots-shop";

  private static MongoClient client;
  private static MongoTemplate template;
  private static InventoryRepository lots;

  @BeforeAll
  static void seed() {
    String uri = System.getProperty("stockkart.test.mongo.uri");
    client = MongoClients.create(uri);
    String db = uri.substring(uri.lastIndexOf('/') + 1).split("\\?")[0] + "_lots";
    template = new MongoTemplate(client, db);
    template.getDb().drop();
    lots = new MongoRepositoryFactory(template).getRepository(InventoryRepository.class);
    for (int i = 0; i < 40; i++) {
      Document lot = new Document("_id", new ObjectId())
          .append("shopId", i % 8 == 0 ? "other" : SHOP)
          .append("lotId", "L" + i)
          .append("productId", "p" + (i % 5))
          .append("vendorId", i % 3 == 0 ? null : "v" + (i % 4))
          .append("location", "Rack " + i)
          .append("receivedCount", new Decimal128(10 + i))
          .append("soldCount", new Decimal128(i % 7))
          .append("currentCount", new Decimal128(10 + i - i % 7))
          .append("receivedBaseCount", 100 + i)
          .append("soldBaseCount", i % 7)
          .append("currentBaseCount", 100 + i - i % 7)
          .append("receivedDate", new Date(1_780_000_000_000L + i * 86_400_000L))
          .append("expiryDate", new Date(1_800_000_000_000L))
          .append("batchNo", "B" + i)
          .append("thresholdCount", 5)
          .append("vendorPurchaseInvoiceId", "vpi" + i)
          .append("createdAt", new Date());
      if (i % 2 == 0) {
        lot.append("pricingId", "pr" + i).append("billingMode", "REGULAR");
      } else {
        lot.append("costPrice", new Decimal128(12)); // legacy lot: price on the lot itself
      }
      template.getCollection("inventory").insertOne(lot);
    }
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

  private static List<Inventory> sorted(List<Inventory> in) {
    return in.stream().sorted(Comparator.comparing(Inventory::getId)).toList();
  }

  @Test
  void sameLotsWithEveryFieldTheTabsUse() {
    List<Inventory> full = sorted(lots.findByShopId(SHOP));
    List<Inventory> slim = sorted(lots.findAnalyticsLotsByShopId(SHOP));

    assertThat(slim).hasSize(35).hasSameSizeAs(full);
    assertThat(slim)
        .usingRecursiveFieldByFieldElementComparatorOnFields(
            "id", "lotId", "productId", "pricingId", "billingMode", "shopId", "vendorId", "location",
            "receivedCount", "soldCount", "currentCount", "receivedBaseCount", "soldBaseCount",
            "currentBaseCount", "receivedDate")
        .containsExactlyElementsOf(full);
  }

  @Test
  void theRestOfTheLotIsNotLoaded() {
    assertThat(lots.findAnalyticsLotsByShopId(SHOP)).allSatisfy(lot -> {
      assertThat(lot.getBatchNo()).isNull();
      assertThat(lot.getExpiryDate()).isNull();
      assertThat(lot.getVendorPurchaseInvoiceId()).isNull();
      assertThat(lot.getCreatedAt()).isNull();
    });
  }
}
