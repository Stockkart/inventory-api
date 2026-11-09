package com.inventory.user.migration;

import static org.assertj.core.api.Assertions.assertThat;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.model.IndexOptions;
import java.util.ArrayList;
import java.util.List;
import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * The index runner against a real MongoDB shaped like production: {@code shop_customers} already
 * has a unique {@code {shopId, customerId}}, {@code shop_vendors} has only {@code _id}.
 *
 * <pre>
 *   mvn -pl core/user -am test -Dtest='ShopPartyIndexMigrationIT' -Dsurefire.failIfNoSpecifiedTests=false \
 *       -Dstockkart.test.mongo.uri=mongodb://localhost:27018/stockkart_test
 * </pre>
 */
@EnabledIfSystemProperty(named = "stockkart.test.mongo.uri", matches = ".+")
class ShopPartyIndexMigrationIT {

  private MongoClient client;
  private MongoTemplate template;
  private ShopPartyIndexMigration migration;

  @BeforeEach
  void setUp() {
    String uri = System.getProperty("stockkart.test.mongo.uri");
    client = MongoClients.create(uri);
    String db = uri.substring(uri.lastIndexOf('/') + 1).split("\\?")[0] + "_idx";
    template = new MongoTemplate(client, db);
    template.getDb().drop();
    template.getCollection("shop_customers").createIndex(
        new Document("shopId", 1).append("customerId", 1), new IndexOptions().unique(true));
    template.getCollection("shop_vendors").insertOne(new Document("shopId", "s").append("vendorId", "v"));
    migration = new ShopPartyIndexMigration();
    ReflectionTestUtils.setField(migration, "mongoTemplate", template);
  }

  @AfterEach
  void tearDown() {
    template.getDb().drop();
    client.close();
  }

  private List<Document> indexes(String collection) {
    return template.getCollection(collection).listIndexes().into(new ArrayList<>());
  }

  @Test
  void keepsTheUniqueCustomerIndexAndAddsTheVendorOne() {
    migration.run();
    migration.run(); // a second start changes nothing

    List<Document> customer = indexes("shop_customers");
    assertThat(customer).hasSize(2);
    Document link = customer.stream().filter(d -> "shopId_1_customerId_1".equals(d.getString("name"))).findFirst().orElseThrow();
    assertThat(link.getBoolean("unique")).isTrue();

    assertThat(indexes("shop_vendors"))
        .extracting(d -> d.get("key", Document.class))
        .contains(new Document("shopId", 1).append("vendorId", 1));
  }
}
