package com.inventory.product.migration;

import com.inventory.product.domain.model.VendorPurchaseInvoice;
import com.inventory.product.domain.repository.VendorPurchaseInvoiceRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.AggregationResults;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

/**
 * One-time migration (dev/prod): align {@code inventory.lotId} with stock-in registration id
 * ({@code vendorPurchaseInvoiceId}), create missing {@code vendor_purchase_invoices} for legacy
 * LOT-* groups, and drop obsolete {@code lotId} on invoice documents.
 */
@Component
@Slf4j
public class LotIdToVendorPurchaseInvoiceMigration {

  private static final String MIGRATION_KEY = "lotId_to_vendor_purchase_invoice_v1";
  private static final String MIGRATIONS = "app_migrations";
  private static final String INVENTORY = "inventory";
  private static final String VENDOR_INVOICES = "vendor_purchase_invoices";

  @Autowired private MongoTemplate mongoTemplate;
  @Autowired private VendorPurchaseInvoiceRepository vendorPurchaseInvoiceRepository;

  @EventListener(ApplicationReadyEvent.class)
  @Order(Integer.MAX_VALUE)
  public void runAfterStartup() {
    if (Boolean.parseBoolean(
        System.getenv().getOrDefault("SKIP_INVENTORY_MIGRATIONS", "false"))) {
      log.info("Skipping {} (SKIP_INVENTORY_MIGRATIONS=true)", MIGRATION_KEY);
      return;
    }
    if (mongoTemplate.exists(Query.query(Criteria.where("_id").is(MIGRATION_KEY)), MIGRATIONS)) {
      return;
    }
    try {
      alignLotIdWithExistingInvoiceId();
      backfillInvoicesForLegacyLots();
      unsetLotIdOnVendorInvoiceDocuments();
      mongoTemplate.save(
          new Document("_id", MIGRATION_KEY).append("appliedAt", Instant.now()), MIGRATIONS);
      log.info("Completed migration {}", MIGRATION_KEY);
    } catch (Exception e) {
      log.error("Migration {} failed — fix data and remove lock document if needed", MIGRATION_KEY, e);
    }
  }

  /** Rows that already have vendorPurchaseInvoiceId: set lotId to the same value. */
  private void alignLotIdWithExistingInvoiceId() {
    var coll = mongoTemplate.getCollection(INVENTORY);
    Document filter =
        Document.parse(
            "{\"$expr\":{\"$and\":["
                + "{\"$ne\":[{\"$ifNull\":[\"$vendorPurchaseInvoiceId\",\"\"]},\"\"]},"
                + "{\"$ne\":[{\"$ifNull\":[\"$lotId\",\"\"]},\"\"]},"
                + "{\"$ne\":[\"$lotId\",\"$vendorPurchaseInvoiceId\"]}"
                + "]}}");
    List<Document> pipeline =
        List.of(new Document("$set", new Document("lotId", "$vendorPurchaseInvoiceId")));
    var result = coll.updateMany(filter, pipeline);
    log.info(
        "alignLotIdWithExistingInvoiceId: matched={}, modified={}",
        result.getMatchedCount(),
        result.getModifiedCount());
  }

  /** Rows with lotId but no vendorPurchaseInvoiceId: create synthetic migrated invoice per group. */
  private void backfillInvoicesForLegacyLots() {
    Aggregation agg =
        Aggregation.newAggregation(
            Aggregation.match(
                new Criteria()
                    .andOperator(
                        Criteria.where("lotId").exists(true).ne(null).ne(""),
                        new Criteria()
                            .orOperator(
                                Criteria.where("vendorPurchaseInvoiceId").exists(false),
                                Criteria.where("vendorPurchaseInvoiceId").is(null),
                                Criteria.where("vendorPurchaseInvoiceId").is("")))),
            Aggregation.group("shopId", "lotId").first("vendorId").as("vendorId"));

    AggregationResults<Document> results =
        mongoTemplate.aggregate(agg, INVENTORY, Document.class);
    int created = 0;
    for (Document doc : results.getMappedResults()) {
      Document id = (Document) doc.get("_id");
      if (id == null) {
        continue;
      }
      String shopId = id.getString("shopId");
      String legacyLotId = id.getString("lotId");
      if (shopId == null || legacyLotId == null) {
        continue;
      }
      String vendorId = doc.getString("vendorId");
      if (vendorId == null || vendorId.isBlank()) {
        vendorId = "UNKNOWN";
      }

      VendorPurchaseInvoice inv = new VendorPurchaseInvoice();
      inv.setShopId(shopId);
      inv.setVendorId(vendorId);
      inv.setInvoiceNo("MIGRATED-" + new ObjectId().toHexString());
      inv.setSynthetic(Boolean.TRUE);
      inv.setLegacyLotId(legacyLotId);
      inv.setLines(new ArrayList<>());
      inv.setCreatedAt(Instant.now());
      inv = vendorPurchaseInvoiceRepository.save(inv);
      String newId = inv.getId();

      Query q =
          new Query(
              new Criteria()
                  .andOperator(
                      Criteria.where("shopId").is(shopId),
                      Criteria.where("lotId").is(legacyLotId),
                      new Criteria()
                          .orOperator(
                              Criteria.where("vendorPurchaseInvoiceId").exists(false),
                              Criteria.where("vendorPurchaseInvoiceId").is(null),
                              Criteria.where("vendorPurchaseInvoiceId").is(""))));
      Update up =
          new Update().set("lotId", newId).set("vendorPurchaseInvoiceId", newId);
      var ur = mongoTemplate.updateMulti(q, up, INVENTORY);
      if (ur.getModifiedCount() > 0) {
        created++;
        log.debug(
            "Backfilled invoice {} for shop {} legacy lot {} ({} rows)",
            newId,
            shopId,
            legacyLotId,
            ur.getModifiedCount());
      }
    }
    log.info("backfillInvoicesForLegacyLots: created {} invoice group(s)", created);
  }

  private void unsetLotIdOnVendorInvoiceDocuments() {
    var ur =
        mongoTemplate.updateMulti(
            new Query(Criteria.where("lotId").exists(true)),
            new Update().unset("lotId"),
            VENDOR_INVOICES);
    log.info(
        "unsetLotIdOnVendorInvoiceDocuments: matched={}, modified={}",
        ur.getMatchedCount(),
        ur.getModifiedCount());
  }
}
