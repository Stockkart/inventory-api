package com.inventory.product.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.regex.Pattern;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.data.mongodb.core.MongoTemplate;

/**
 * Purchase-invoice search against a real MongoDB, checked page by page against the old in-Java
 * filter (re-implemented here over the same raw documents).
 *
 * <pre>
 *   docker run -d --rm --name stockkart-test-mongo -p 27018:27017 --tmpfs /data/db mongo:7
 *   mvn -pl core/product -am test -Dtest='VendorPurchaseInvoiceSearchIT' -Dsurefire.failIfNoSpecifiedTests=false \
 *       -Dstockkart.test.mongo.uri=mongodb://localhost:27018/stockkart_test
 * </pre>
 * Seeds edge cases on purpose: invoices with no createdAt and with equal createdAt, a vendor id
 * stored with spaces, unknown vendors, null / blank / padded names, barcodes and invoice numbers,
 * invoices with no lines, and another shop's invoices that match.
 */
@EnabledIfSystemProperty(named = "stockkart.test.mongo.uri", matches = ".+")
class VendorPurchaseInvoiceSearchIT {

  private static final String SHOP = "inv-shop";
  private static final String OTHER = "other-shop";

  private static MongoClient client;
  private static MongoTemplate template;
  private static VendorPurchaseInvoiceSearch search;
  private static final List<Document> invoices = new ArrayList<>();
  private static final Map<String, String> vendorNames = new HashMap<>();

  @BeforeAll
  static void seed() {
    String uri = System.getProperty("stockkart.test.mongo.uri");
    client = MongoClients.create(uri);
    String db = uri.substring(uri.lastIndexOf('/') + 1).split("\\?")[0] + "_vpi";
    template = new MongoTemplate(client, db);
    template.getDb().drop();
    search = new VendorPurchaseInvoiceSearch(template);

    String[] vendorNameList = {"Cipla Distributors", "  Sun Pharma Agencies ", "Ravi Medical", null, "   "};
    List<String> vendorIds = new ArrayList<>();
    for (String name : vendorNameList) {
      ObjectId id = new ObjectId();
      Document v = new Document("_id", id);
      if (name != null) {
        v.append("name", name);
      }
      template.getCollection("vendors").insertOne(v);
      vendorIds.add(id.toHexString());
      vendorNames.put(id.toHexString(), name);
    }
    vendorIds.add(new ObjectId().toHexString()); // a vendor that no longer exists
    vendorIds.add(" " + vendorIds.get(0) + " "); // stored with spaces: still Cipla

    String[] productNames = {"Paracetamol 500", " DOLO 650 ", "Azithral", "", null, "Cipla Inhaler", "pan-D"};
    String[] barcodes = {"8901234567890", null, "  ", " 8909999000011", "ABC-123"};
    String[] invoiceNos = {"INV-001", " inv-002", "", null, "B/2026/77", "INV-001X"};
    Random rnd = new Random(11);
    Instant base = Instant.parse("2026-09-01T00:00:00Z");
    for (int i = 0; i < 90; i++) {
      Document inv = new Document("_id", new ObjectId())
          .append("shopId", i % 5 == 0 ? OTHER : SHOP)
          .append("vendorId", vendorIds.get(rnd.nextInt(vendorIds.size())));
      String no = invoiceNos[rnd.nextInt(invoiceNos.length)];
      if (no != null) {
        inv.append("invoiceNo", no + (no.isBlank() ? "" : "-" + i));
      }
      if (i % 9 != 0) { // every ninth has no createdAt
        inv.append("createdAt", Date.from(base.plusSeconds((i / 3) * 3600L))); // threes share a time
      }
      if (i % 11 != 0) { // every eleventh has no lines at all
        List<Document> lines = new ArrayList<>();
        for (int l = 0, n = rnd.nextInt(4); l < n; l++) {
          Document line = new Document("lineIndex", l);
          String name = productNames[rnd.nextInt(productNames.length)];
          String barcode = barcodes[rnd.nextInt(barcodes.length)];
          if (name != null) {
            line.append("name", name);
          }
          if (barcode != null) {
            line.append("barcode", barcode);
          }
          lines.add(line);
        }
        inv.append("lines", lines);
      }
      template.getCollection("vendor_purchase_invoices").insertOne(inv);
      invoices.add(inv);
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

  private static boolean find(Pattern p, Object value) {
    return value instanceof String s && !s.isBlank() && p.matcher(s.trim()).find();
  }

  /** The old service: every invoice of the shop, filtered in Java, sorted, then sliced. */
  private static List<String> oldSearch(String query) {
    Pattern p = Pattern.compile(query.trim(), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    List<Document> hits = new ArrayList<>();
    for (Document inv : invoices) {
      if (!SHOP.equals(inv.getString("shopId"))) {
        continue;
      }
      String vid = inv.getString("vendorId");
      boolean match = find(p, inv.get("invoiceNo"))
          || find(p, vid == null || vid.isBlank() ? null : vendorNames.get(vid.trim()));
      for (Document line : inv.getList("lines", Document.class, List.of())) {
        match = match || find(p, line.get("name")) || find(p, line.get("barcode"));
      }
      if (match) {
        hits.add(inv);
      }
    }
    hits.sort(Comparator.comparing((Document d) -> d.getDate("createdAt"), Comparator.nullsLast(Comparator.reverseOrder()))
        .thenComparing(d -> d.getObjectId("_id").toHexString(), Comparator.reverseOrder()));
    return hits.stream().map(d -> d.getObjectId("_id").toHexString()).toList();
  }

  @Test
  void everyPageMatchesTheOldFilter() {
    for (String q : Arrays.asList("cipla", "CIPLA", "sun pharma", "^sun", "dolo", "^dolo 650$", "8909", "^8909",
        "inv-00", "^inv", "B/2026", "abc-123", "medical", "zzz-none", "a*", "\\d{3}")) {
      List<String> old = oldSearch(q);
      List<String> got = new ArrayList<>();
      long total = -1;
      for (int page = 0; page < 30; page++) {
        VendorPurchaseInvoiceSearch.InvoicePage p = search.search(SHOP, q.trim(), page, 7);
        total = p.total();
        p.invoices().forEach(inv -> got.add(inv.getId()));
        if (p.invoices().size() < 7) {
          break;
        }
      }
      assertThat(total).as(q).isEqualTo(old.size());
      assertThat(got).as(q).containsExactlyElementsOf(old);
    }
  }

  @Test
  void theSeedCoversTheEdgeCases() {
    assertThat(oldSearch("cipla")).isNotEmpty();
    assertThat(oldSearch("^sun")).isNotEmpty(); // only matches the padded vendor name once trimmed
    assertThat(oldSearch("a*").size()).isLessThan((int) invoices.stream().filter(d -> SHOP.equals(d.get("shopId"))).count());
  }

  @Test
  void pagesCarryWholeInvoices() {
    VendorPurchaseInvoiceSearch.InvoicePage p = search.search(SHOP, "dolo", 0, 3);
    assertThat(p.invoices()).isNotEmpty().allSatisfy(inv -> {
      assertThat(inv.getShopId()).isEqualTo(SHOP);
      assertThat(inv.getLines()).anySatisfy(l -> assertThat(l.getName()).containsIgnoringCase("dolo"));
    });
  }
}
