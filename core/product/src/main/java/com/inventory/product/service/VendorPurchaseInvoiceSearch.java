package com.inventory.product.service;

import com.inventory.product.domain.model.VendorPurchaseInvoice;
import com.inventory.user.domain.model.Vendor;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

/**
 * Text search over one shop's purchase invoices, done in MongoDB.
 *
 * <p>An invoice matches when the (case-insensitive) regex finds a match in its invoice number, its
 * vendor's name, or any line's product name or barcode — each value trimmed, blanks never
 * matching, as the old in-Java filter did. The old filter loaded every invoice of the shop (all
 * lines included) to show one page; now the database filters, sorts newest first ({@code
 * createdAt} desc, then id desc, no date last) and pages, and only the page's invoices are read.
 * The sort carries just {@code _id} and {@code createdAt}, so it stays far below M0's 32 MB
 * in-memory sort limit however large the invoices are.
 */
@Component
public class VendorPurchaseInvoiceSearch {

  /** One page of matches plus the number of all matches. */
  public record InvoicePage(List<VendorPurchaseInvoice> invoices, long total) {}

  private final MongoTemplate mongoTemplate;

  public VendorPurchaseInvoiceSearch(MongoTemplate mongoTemplate) {
    this.mongoTemplate = mongoTemplate;
  }

  /** {@code regex} must already be a valid pattern; {@code page} is 0-based. */
  public InvoicePage search(String shopId, String regex, int page, int size) {
    List<Object> vendorIds = vendorIdsMatching(shopId, regex);
    Document anyMatch =
        new Document(
            "$or",
            Arrays.asList(
                matches("$invoiceNo", regex),
                new Document("$in", Arrays.asList("$vendorId", vendorIds)),
                new Document(
                    "$anyElementTrue",
                    List.of(
                        new Document(
                            "$map",
                            new Document("input", new Document("$ifNull", Arrays.asList("$lines", List.of())))
                                .append("as", "l")
                                .append(
                                    "in",
                                    new Document(
                                        "$or",
                                        Arrays.asList(matches("$$l.name", regex), matches("$$l.barcode", regex)))))))));
    List<Document> pipeline =
        List.of(
            new Document("$match", new Document("shopId", shopId).append("$expr", anyMatch)),
            new Document("$project", new Document("createdAt", 1)),
            new Document("$sort", new Document("createdAt", -1).append("_id", -1)),
            new Document(
                "$facet",
                new Document("total", List.of(new Document("$count", "n")))
                    .append(
                        "page",
                        List.of(
                            new Document("$skip", (long) page * size),
                            new Document("$limit", Math.max(size, 1)),
                            new Document("$project", new Document("_id", 1))))));
    Document facet =
        mongoTemplate.getCollection(collection()).aggregate(pipeline).first();
    if (facet == null) {
      return new InvoicePage(List.of(), 0);
    }
    List<Document> totalRows = facet.getList("total", Document.class, List.of());
    long total = totalRows.isEmpty() ? 0 : ((Number) totalRows.get(0).get("n")).longValue();
    List<Object> pageIds = new ArrayList<>();
    for (Document d : facet.getList("page", Document.class, List.of())) {
      pageIds.add(d.get("_id"));
    }
    if (size <= 0 || pageIds.isEmpty()) {
      return new InvoicePage(List.of(), total);
    }
    Map<String, VendorPurchaseInvoice> byId = new HashMap<>();
    for (VendorPurchaseInvoice inv :
        mongoTemplate.find(new Query(Criteria.where("_id").in(pageIds)), VendorPurchaseInvoice.class)) {
      byId.put(inv.getId(), inv);
    }
    List<VendorPurchaseInvoice> ordered = new ArrayList<>();
    for (Object id : pageIds) {
      VendorPurchaseInvoice inv = byId.get(String.valueOf(id));
      if (inv != null) {
        ordered.add(inv);
      }
    }
    return new InvoicePage(ordered, total);
  }

  /**
   * The {@code vendorId} values (as stored on this shop's invoices) whose vendor's name matches.
   * Reads the shop's distinct vendor ids from the {@code {shopId, vendorId, invoiceNo}} index, then
   * just those vendors.
   */
  private List<Object> vendorIdsMatching(String shopId, String regex) {
    Map<String, List<String>> storedByTrimmed = new HashMap<>();
    for (String stored :
        mongoTemplate
            .getCollection(collection())
            .distinct("vendorId", new Document("shopId", shopId), String.class)) {
      if (stored != null && !stored.isBlank()) {
        storedByTrimmed.computeIfAbsent(stored.trim(), k -> new ArrayList<>()).add(stored);
      }
    }
    if (storedByTrimmed.isEmpty()) {
      return List.of();
    }
    List<Object> keys = new ArrayList<>();
    for (String id : storedByTrimmed.keySet()) {
      keys.add(ObjectId.isValid(id) ? new ObjectId(id) : id);
    }
    Document filter =
        new Document("_id", new Document("$in", keys)).append("$expr", matches("$name", regex));
    Set<Object> out = new HashSet<>();
    for (Document v :
        mongoTemplate
            .getCollection(mongoTemplate.getCollectionName(Vendor.class))
            .find(filter)
            .projection(new Document("_id", 1))) {
      out.addAll(storedByTrimmed.getOrDefault(String.valueOf(v.get("_id")), List.of()));
    }
    return new ArrayList<>(out);
  }

  /** True when {@code field} is a non-blank string whose trimmed value the regex finds a match in. */
  private static Document matches(String field, String regex) {
    Document trimmed = new Document("$trim", new Document("input", field));
    // $and stops at the first false, so $trim never sees a non-string
    return new Document(
        "$and",
        Arrays.asList(
            new Document("$eq", Arrays.asList(new Document("$type", field), "string")),
            new Document("$gt", Arrays.asList(new Document("$strLenCP", trimmed), 0)),
            new Document(
                "$regexMatch",
                new Document("input", trimmed).append("regex", regex).append("options", "i"))));
  }

  private String collection() {
    return mongoTemplate.getCollectionName(VendorPurchaseInvoice.class);
  }
}
