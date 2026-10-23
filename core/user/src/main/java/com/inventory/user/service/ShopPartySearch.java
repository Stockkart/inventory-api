package com.inventory.user.service;

import com.inventory.user.domain.model.Customer;
import com.inventory.user.domain.model.ShopCustomer;
import com.inventory.user.domain.model.ShopVendor;
import com.inventory.user.domain.model.Vendor;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

/**
 * Text search over one shop's customers or vendors, done in MongoDB.
 *
 * <p>Customers and vendors are shared across shops (a shop reaches its own through
 * {@code shop_customers} / {@code shop_vendors}). The search used to run its regex over every
 * customer (or vendor) of every shop and then keep the shop's in Java — its cost grew with the
 * platform, not the shop. Now it reads the shop's ids (index-only on the link collection) and runs
 * the same regex over just those documents. Fields matched and the regex itself are unchanged.
 */
@Component
public class ShopPartySearch {

  private static final String[] CUSTOMER_FIELDS = {"name", "phone", "email", "address", "gstin", "pan", "dlNo"};
  private static final String[] VENDOR_FIELDS = {"name", "companyName", "contactEmail", "contactPhone", "address"};

  private final MongoTemplate mongoTemplate;

  public ShopPartySearch(MongoTemplate mongoTemplate) {
    this.mongoTemplate = mongoTemplate;
  }

  /** One page of the shop's (non-general) customers matching {@code query}, newest change first. */
  public record CustomerPage(List<Customer> customers, long total) {}

  /** Ids of the shop's customers matching {@code query}; the walk-in placeholder only if asked for. */
  public List<String> customerIdsMatching(String shopId, String query, boolean includeGeneral) {
    List<Object> ids = linkedIds(ShopCustomer.class, "customerId", shopId);
    if (ids.isEmpty()) {
      return List.of();
    }
    Criteria c = Criteria.where("_id").in(ids).andOperator(anyFieldMatches(CUSTOMER_FIELDS, query));
    if (!includeGeneral) {
      c = c.and("isGeneral").ne(true);
    }
    Query q = new Query(c);
    q.fields().include("_id");
    List<String> out = new ArrayList<>();
    for (Document d : mongoTemplate.find(q, Document.class, collection(Customer.class))) {
      out.add(String.valueOf(d.get("_id")));
    }
    return out;
  }

  /**
   * The shop's non-general customers matching {@code query}, ordered by last change (updatedAt,
   * else createdAt) newest first, and paged — all in one aggregation.
   */
  public CustomerPage customersMatching(String shopId, String query, int page, int limit) {
    List<Object> ids = linkedIds(ShopCustomer.class, "customerId", shopId);
    if (ids.isEmpty()) {
      return new CustomerPage(List.of(), 0);
    }
    Document match =
        Criteria.where("_id").in(ids).and("isGeneral").ne(true)
            .andOperator(anyFieldMatches(CUSTOMER_FIELDS, query)).getCriteriaObject();
    List<Document> pipeline =
        List.of(
            new Document("$match", match),
            new Document("$addFields", new Document("_sortAt", new Document("$ifNull", Arrays.asList("$updatedAt", "$createdAt")))),
            new Document("$sort", new Document("_sortAt", -1).append("_id", 1)),
            new Document(
                "$facet",
                new Document("total", List.of(new Document("$count", "n")))
                    .append("page", List.of(new Document("$skip", (long) page * limit), new Document("$limit", limit)))));
    Document facet =
        mongoTemplate.getCollection(collection(Customer.class)).aggregate(pipeline).first();
    if (facet == null) {
      return new CustomerPage(List.of(), 0);
    }
    List<Document> totalRows = facet.getList("total", Document.class, List.of());
    long total = totalRows.isEmpty() ? 0 : ((Number) totalRows.get(0).get("n")).longValue();
    List<Customer> customers = new ArrayList<>();
    for (Document d : facet.getList("page", Document.class, List.of())) {
      d.remove("_sortAt");
      customers.add(mongoTemplate.getConverter().read(Customer.class, d));
    }
    return new CustomerPage(customers, total);
  }

  /** The shop's vendors matching {@code query}. */
  public List<Vendor> vendorsMatching(String shopId, String query) {
    List<Object> ids = linkedIds(ShopVendor.class, "vendorId", shopId);
    if (ids.isEmpty()) {
      return List.of();
    }
    return mongoTemplate.find(
        new Query(Criteria.where("_id").in(ids).andOperator(anyFieldMatches(VENDOR_FIELDS, query))), Vendor.class);
  }

  /** The ids a shop links to, read from the link collection only (covered by {shopId, <id>}). */
  private List<Object> linkedIds(Class<?> link, String idField, String shopId) {
    Query q = new Query(Criteria.where("shopId").is(shopId));
    q.fields().include(idField).exclude("_id");
    List<Object> out = new ArrayList<>();
    for (Document d : mongoTemplate.find(q, Document.class, collection(link))) {
      Object id = d.get(idField);
      if (id instanceof String s && !s.isBlank()) {
        out.add(ObjectId.isValid(s) ? new ObjectId(s) : s);
      }
    }
    return out;
  }

  /** {@code $or} of case-insensitive regexes, as the repositories' {@code searchByQuery} did. */
  private static Criteria anyFieldMatches(String[] fields, String query) {
    Criteria[] any = new Criteria[fields.length];
    for (int i = 0; i < fields.length; i++) {
      any[i] = Criteria.where(fields[i]).regex(query, "i");
    }
    return new Criteria().orOperator(any);
  }

  private String collection(Class<?> type) {
    return mongoTemplate.getCollectionName(type);
  }
}
