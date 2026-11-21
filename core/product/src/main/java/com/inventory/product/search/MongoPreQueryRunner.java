package com.inventory.product.search;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Projections;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

/** {@link PreQueryRunner} backed by the real collections. */
@Component
public class MongoPreQueryRunner implements PreQueryRunner {

  static final String PRODUCT_COLLECTION = "product";
  static final int MAX_IDS = 50_000;
  static final long MAX_TIME_MS = 2_000;

  private final MongoTemplate mongoTemplate;

  public MongoPreQueryRunner(MongoTemplate mongoTemplate) {
    this.mongoTemplate = mongoTemplate;
  }

  @Override
  public List<String> distinctValues(String collection, String shopId, String field, int max) {
    MongoCollection<Document> col = mongoTemplate.getCollection(collection);
    List<String> values = new ArrayList<>();
    for (String v : col.distinct(field, new Document("shopId", shopId), String.class).maxTime(MAX_TIME_MS, TimeUnit.MILLISECONDS)) {
      if (v == null) {
        continue;
      }
      values.add(v);
      if (values.size() > max) {
        return null;
      }
    }
    return values;
  }

  @Override
  public List<String> productIds(String shopId, Document filter) {
    Document full = new Document("shopId", shopId);
    full.putAll(filter);
    MongoCollection<Document> col = mongoTemplate.getCollection(PRODUCT_COLLECTION);
    List<String> ids = new ArrayList<>();
    for (Document d :
        col.find(full).projection(Projections.include("_id")).limit(MAX_IDS).maxTime(MAX_TIME_MS, TimeUnit.MILLISECONDS)) {
      Object id = d.get("_id");
      if (id != null) {
        ids.add(id.toString());
      }
    }
    return ids;
  }

  @Override
  public List<String> extensionInventoryIds(String collection, String shopId, Document filter) {
    Document full = new Document("shopId", shopId);
    full.putAll(filter);
    MongoCollection<Document> col = mongoTemplate.getCollection(collection);
    List<String> ids = new ArrayList<>();
    for (Document d :
        col.find(full).projection(Projections.include("inventoryId")).limit(MAX_IDS).maxTime(MAX_TIME_MS, TimeUnit.MILLISECONDS)) {
      Object id = d.get("inventoryId");
      if (id != null) {
        ids.add(id.toString());
      }
    }
    return ids;
  }
}
