package com.inventory.product.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventory.product.domain.model.Purchase;
import com.inventory.product.domain.repository.PurchaseRepository;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.repository.support.MongoRepositoryFactory;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Refund search's bill-id lookup against a real MongoDB: the ids-only read returns the same bills
 * as the old full-document repository reads, for invoice number, customer, and both.
 *
 * <pre>
 *   docker run -d --rm --name stockkart-test-mongo -p 27018:27017 --tmpfs /data/db mongo:7
 *   mvn -pl core/product -am test -Dtest='RefundPurchaseIdsIT' -Dsurefire.failIfNoSpecifiedTests=false \
 *       -Dstockkart.test.mongo.uri=mongodb://localhost:27018/stockkart_test
 * </pre>
 */
@EnabledIfSystemProperty(named = "stockkart.test.mongo.uri", matches = ".+")
class RefundPurchaseIdsIT {

  private static final String SHOP = "refund-shop";

  private static MongoClient client;
  private static MongoTemplate template;
  private static PurchaseRepository purchases;
  private static RefundService service;

  @BeforeAll
  static void seed() {
    String uri = System.getProperty("stockkart.test.mongo.uri");
    client = MongoClients.create(uri);
    String db = uri.substring(uri.lastIndexOf('/') + 1).split("\\?")[0] + "_refund";
    template = new MongoTemplate(client, db);
    template.getDb().drop();
    purchases = new MongoRepositoryFactory(template).getRepository(PurchaseRepository.class);
    service = new RefundService();
    ReflectionTestUtils.setField(service, "mongoTemplate", template);

    String[] customers = {"c-regular", "c-other", null};
    String[] invoices = {"INV-1", "INV-2", "INV-3", null};
    for (int i = 0; i < 300; i++) {
      Document bill = new Document("_id", new ObjectId())
          .append("shopId", i % 10 == 0 ? "other-shop" : SHOP)
          .append("items", List.of(new Document("name", "line " + i).append("quantity", 1)));
      String c = customers[i % customers.length];
      String n = invoices[i % invoices.length];
      if (c != null) {
        bill.append("customerId", c);
      }
      if (n != null) {
        bill.append("invoiceNo", n);
      }
      template.getCollection("purchases").insertOne(bill);
    }
    // a bill whose id is a plain string, not an ObjectId
    template.getCollection("purchases").insertOne(
        new Document("_id", "legacy-bill-1").append("shopId", SHOP).append("customerId", "c-regular"));
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

  private static List<String> ids(List<Purchase> bills) {
    return bills.stream().map(Purchase::getId).toList();
  }

  @Test
  void sameBillsAsTheOldFullReads() {
    for (String c : Arrays.asList("c-regular", "c-other", "nobody")) {
      assertThat(service.purchaseIdsMatching(SHOP, null, c))
          .as(c).containsExactlyInAnyOrderElementsOf(ids(purchases.findByShopIdAndCustomerId(SHOP, c)));
      for (String n : Arrays.asList("INV-1", "INV-2", "INV-9")) {
        assertThat(service.purchaseIdsMatching(SHOP, n, c))
            .as(n + "/" + c)
            .containsExactlyInAnyOrderElementsOf(ids(purchases.findByShopIdAndInvoiceNoAndCustomerId(SHOP, n, c)));
      }
    }
    for (String n : Arrays.asList("INV-1", "INV-3", "INV-9")) {
      assertThat(service.purchaseIdsMatching(SHOP, n, null))
          .as(n).containsExactlyInAnyOrderElementsOf(ids(purchases.findByShopIdAndInvoiceNo(SHOP, n)));
    }
  }

  @Test
  void theSeedHasWhatTheComparisonNeeds() {
    List<String> regular = new ArrayList<>(service.purchaseIdsMatching(SHOP, null, "c-regular"));
    assertThat(regular).hasSizeGreaterThan(50).contains("legacy-bill-1");
    assertThat(service.purchaseIdsMatching("other-shop", null, "c-regular")).doesNotContainAnyElementsOf(regular);
  }
}
