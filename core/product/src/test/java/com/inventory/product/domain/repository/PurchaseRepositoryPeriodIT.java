package com.inventory.product.domain.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.inventory.product.domain.model.Purchase;
import com.inventory.product.domain.model.enums.PurchaseStatus;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import org.bson.Document;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.repository.support.MongoRepositoryFactory;

/**
 * Which sales a GSTR-1 period picks up, against a real MongoDB.
 *
 * <p>Runs only when {@code -Dstockkart.test.mongo.uri=…} is set. Locally:
 *
 * <pre>
 *   docker run -d --rm --name stockkart-test-mongo -p 27018:27017 --tmpfs /data/db mongo:7
 *   mvn -pl core/product -am test -Dtest='PurchaseRepositoryPeriodIT' \
 *       -Dsurefire.failIfNoSpecifiedTests=false \
 *       -Dstockkart.test.mongo.uri=mongodb://localhost:27018/stockkart_test
 *   docker stop stockkart-test-mongo
 * </pre>
 */
@EnabledIfSystemProperty(named = "stockkart.test.mongo.uri", matches = ".+")
class PurchaseRepositoryPeriodIT {

  private static final String SHOP = "it-shop";
  private static final Instant OCT_START = Instant.parse("2026-09-30T18:30:00Z");
  private static final Instant NOV_START = Instant.parse("2026-10-31T18:30:00Z");

  private static MongoClient client;
  private static PurchaseRepository purchases;

  @BeforeAll
  static void seed() {
    String uri = System.getProperty("stockkart.test.mongo.uri");
    client = MongoClients.create(uri);
    String db = uri.substring(uri.lastIndexOf('/') + 1).split("\\?")[0];
    MongoTemplate template = new MongoTemplate(client, db);
    template.getDb().drop();
    purchases = new MongoRepositoryFactory(template).getRepository(PurchaseRepository.class);

    template.getCollection("purchases").insertMany(List.of(
        // Sold in October.
        sale("T001400", "2026-10-10T06:00:00Z", "2026-10-10T06:00:00Z"),
        // Sold 30 September, renumbered and backdated on 5 October: already filed for September.
        sale("T001199", "2026-09-30T14:07:00Z", "2026-10-05T15:30:00Z"),
        // A legacy sale with no soldAt, completed in October.
        sale("LEGACY-1", null, "2026-10-12T06:00:00Z"),
        // A legacy sale with no soldAt, completed in September.
        sale("LEGACY-2", null, "2026-09-12T06:00:00Z")));
  }

  @AfterAll
  static void close() {
    if (client != null) client.close();
  }

  private static Document sale(String invoiceNo, String soldAt, String updatedAt) {
    Document d = new Document("shopId", SHOP)
        .append("status", PurchaseStatus.COMPLETED.name())
        .append("invoiceNo", invoiceNo)
        .append("updatedAt", Date.from(Instant.parse(updatedAt)));
    if (soldAt != null) d.append("soldAt", Date.from(Instant.parse(soldAt)));
    return d;
  }

  private static List<String> octoberInvoices() {
    return purchases
        .findCompletedPurchasesInPeriod(SHOP, PurchaseStatus.COMPLETED, OCT_START, NOV_START)
        .stream()
        .map(Purchase::getInvoiceNo)
        .sorted()
        .toList();
  }

  @Test
  void aSaleSoldInAnEarlierMonthIsNotFiledAgainWhenEditedLater() {
    assertEquals(List.of("LEGACY-1", "T001400"), octoberInvoices());
  }
}
