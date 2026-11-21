package com.inventory.user.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventory.user.domain.model.Customer;
import com.inventory.user.domain.model.Vendor;
import com.inventory.user.domain.repository.CustomerRepository;
import com.inventory.user.domain.repository.VendorRepository;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.repository.support.MongoRepositoryFactory;

/**
 * Shop-scoped customer/vendor search against a real MongoDB, checked against the old way: the
 * repositories' regex over every shop's customers (vendors), then kept to the shop in Java.
 *
 * <pre>
 *   docker run -d --rm --name stockkart-test-mongo -p 27018:27017 --tmpfs /data/db mongo:7
 *   mvn -pl core/user -am test -Dtest='ShopPartySearchIT' -Dsurefire.failIfNoSpecifiedTests=false \
 *       -Dstockkart.test.mongo.uri=mongodb://localhost:27018/stockkart_test
 * </pre>
 */
@EnabledIfSystemProperty(named = "stockkart.test.mongo.uri", matches = ".+")
class ShopPartySearchIT {

  private static final String SHOP = "shop-a";
  private static final String OTHER = "shop-b";

  private static MongoClient client;
  private static MongoTemplate template;
  private static ShopPartySearch search;
  private static CustomerRepository customers;
  private static VendorRepository vendors;
  private static final Set<String> shopCustomerIds = new HashSet<>();
  private static final Set<String> shopVendorIds = new HashSet<>();

  @BeforeAll
  static void seed() {
    String uri = System.getProperty("stockkart.test.mongo.uri");
    client = MongoClients.create(uri);
    String db = uri.substring(uri.lastIndexOf('/') + 1).split("\\?")[0];
    template = new MongoTemplate(client, db);
    template.getDb().drop();
    search = new ShopPartySearch(template);
    MongoRepositoryFactory repos = new MongoRepositoryFactory(template);
    customers = repos.getRepository(CustomerRepository.class);
    vendors = repos.getRepository(VendorRepository.class);

    String[] names = {"Ravi Medical", "RAVI KUMAR", "Asha", "Abid Medical Hall Barachatti", "Nafees", "ravindra"};
    Instant base = Instant.parse("2026-10-01T00:00:00Z");
    for (int i = 0; i < 60; i++) {
      ObjectId id = new ObjectId();
      Document c = new Document("_id", id).append("name", names[i % names.length] + " " + i)
          .append("phone", "98" + String.format("%08d", i)).append("createdAt", Date.from(base.plusSeconds(i * 60L)));
      if (i % 4 == 0) {
        c.append("updatedAt", Date.from(base.plusSeconds(100_000L + i))); // edited later: sorts first
      }
      if (i % 7 == 0) {
        c.append("address", "Near Ravi talkies");
      }
      if (i == 5) {
        c.append("isGeneral", true).put("name", "Walk-in Ravi");
      }
      template.getCollection("customers").insertOne(c);
      String shop = i % 3 == 0 ? OTHER : SHOP; // a third belong to another shop
      template.getCollection("shop_customers").insertOne(
          new Document("shopId", shop).append("customerId", id.toHexString()));
      if (shop.equals(SHOP)) {
        shopCustomerIds.add(id.toHexString());
      }
    }
    for (int i = 0; i < 20; i++) {
      ObjectId id = new ObjectId();
      template.getCollection("vendors").insertOne(new Document("_id", id)
          .append("name", (i % 2 == 0 ? "Cipla Distributors " : "Sun Agencies ") + i)
          .append("companyName", i % 5 == 0 ? "Ravi Pharma" : null).append("createdAt", Date.from(base)));
      String shop = i % 4 == 0 ? OTHER : SHOP;
      template.getCollection("shop_vendors").insertOne(new Document("shopId", shop).append("vendorId", id.toHexString()));
      if (shop.equals(SHOP)) {
        shopVendorIds.add(id.toHexString());
      }
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

  /** The old customers-page search: every shop's matches, kept to this shop, general dropped, sorted. */
  private static List<Customer> oldCustomerSearch(String query) {
    List<Customer> out = new ArrayList<>();
    for (Customer c : customers.searchByQuery(query)) {
      if (shopCustomerIds.contains(c.getId()) && !c.isGeneralCustomer()) {
        out.add(c);
      }
    }
    out.sort(Comparator.comparing((Customer c) -> c.getUpdatedAt() != null ? c.getUpdatedAt() : c.getCreatedAt()).reversed());
    return out;
  }

  @Test
  void customersPageMatchesTheOldSearchPageByPage() {
    for (String q : List.of("ravi", "RAVI", "medical", "98000000", "talkies", "nothing-like-this", "^Asha")) {
      List<String> old = oldCustomerSearch(q).stream().map(Customer::getId).toList();
      List<String> got = new ArrayList<>();
      long total = -1;
      for (int page = 0; page < 10; page++) {
        ShopPartySearch.CustomerPage p = search.customersMatching(SHOP, q, page, 4);
        total = p.total();
        p.customers().forEach(c -> got.add(c.getId()));
        if (p.customers().size() < 4) {
          break;
        }
      }
      assertThat(total).as(q).isEqualTo(old.size());
      assertThat(got).as(q).containsExactlyElementsOf(old);
    }
  }

  @Test
  void pagesCarryWholeCustomers() {
    // The page must hold the full stored customer, not a projection of the fields the search touched.
    Customer first = search.customersMatching(SHOP, "ravi", 0, 1).customers().get(0);
    Customer stored = customers.findById(first.getId()).orElseThrow();
    assertThat(first).usingRecursiveComparison().isEqualTo(stored);
    assertThat(first.getPhone()).startsWith("98");
  }

  @Test
  void idsMatchTheOldSearchWithAndWithoutTheWalkInPlaceholder() {
    Set<String> withGeneral = new HashSet<>();
    for (Customer c : customers.searchByQuery("ravi")) {
      if (shopCustomerIds.contains(c.getId())) {
        withGeneral.add(c.getId());
      }
    }
    assertThat(search.customerIdsMatching(SHOP, "ravi", true)).containsExactlyInAnyOrderElementsOf(withGeneral);
    Set<String> withoutGeneral = new HashSet<>(withGeneral);
    withoutGeneral.removeIf(id -> customers.findById(id).orElseThrow().isGeneralCustomer());
    assertThat(withoutGeneral).hasSize(withGeneral.size() - 1); // the walk-in "Walk-in Ravi" is this shop's
    assertThat(search.customerIdsMatching(SHOP, "ravi", false)).containsExactlyInAnyOrderElementsOf(withoutGeneral);
  }

  @Test
  void vendorsMatchTheOldSearch() {
    for (String q : List.of("cipla", "ravi pharma", "AGENCIES", "zzz")) {
      Set<String> old = new HashSet<>();
      for (Vendor v : vendors.searchByQuery(q)) {
        if (shopVendorIds.contains(v.getId())) {
          old.add(v.getId());
        }
      }
      assertThat(search.vendorsMatching(SHOP, q).stream().map(Vendor::getId).toList())
          .as(q).containsExactlyInAnyOrderElementsOf(old);
    }
  }

  @Test
  void aShopWithNoCustomersOrVendorsFindsNothing() {
    assertThat(search.customersMatching("empty-shop", "ravi", 0, 20).total()).isZero();
    assertThat(search.customerIdsMatching("empty-shop", "ravi", true)).isEmpty();
    assertThat(search.vendorsMatching("empty-shop", "cipla")).isEmpty();
  }
}
