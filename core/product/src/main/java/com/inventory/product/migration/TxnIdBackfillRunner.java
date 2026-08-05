package com.inventory.product.migration;

import com.inventory.common.util.TxnIdGenerator;
import com.inventory.credit.domain.model.CreditEntry;
import com.inventory.product.domain.model.Purchase;
import com.inventory.product.domain.model.Refund;
import com.inventory.product.domain.model.VendorPurchaseInvoice;
import com.inventory.product.domain.model.VendorPurchaseReturn;
import com.inventory.product.domain.model.enums.PurchaseStatus;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Idempotent backfill: assigns system-wide UUID {@code txnId} to legacy money documents that lack
 * one. Enable with {@code stockkart.txn-id-backfill.enabled=true}.
 */
@Component
@Slf4j
public class TxnIdBackfillRunner {

  @Autowired private MongoTemplate mongoTemplate;

  @Value("${stockkart.txn-id-backfill.enabled:false}")
  private boolean enabled;

  @Value("${stockkart.txn-id-backfill.dry-run:true}")
  private boolean dryRun;

  @EventListener(ApplicationReadyEvent.class)
  @Order(25)
  public void run() {
    if (!enabled) {
      return;
    }
    log.info("[txn-id-backfill] starting (dryRun={})", dryRun);
    long invoices = backfillCollection(VendorPurchaseInvoice.class, "vendor_purchase_invoices");
    long returns = backfillCollection(VendorPurchaseReturn.class, "vendor_purchase_returns");
    long sales = backfillCompletedPurchases();
    long refunds = backfillCollection(Refund.class, "refunds");
    long credits = backfillCollection(CreditEntry.class, "credit_entries");
    log.info(
        "[txn-id-backfill] done (dryRun={}): invoices={}, returns={}, sales={}, refunds={}, credits={}",
        dryRun,
        invoices,
        returns,
        sales,
        refunds,
        credits);
  }

  private long backfillCollection(Class<?> entityClass, String label) {
    Query missing =
        new Query(
            new Criteria()
                .orOperator(
                    Criteria.where("txnId").is(null),
                    Criteria.where("txnId").is(""),
                    Criteria.where("txnId").exists(false)));
    List<?> rows = mongoTemplate.find(missing, entityClass);
    long updated = 0;
    for (Object row : rows) {
      String id = readId(row);
      if (!StringUtils.hasText(id)) {
        continue;
      }
      if (dryRun) {
        updated++;
        continue;
      }
      Update update = new Update().set("txnId", TxnIdGenerator.newId());
      mongoTemplate.updateFirst(Query.query(Criteria.where("_id").is(id)), update, entityClass);
      updated++;
    }
    log.info("[txn-id-backfill] {}: {} row(s)", label, updated);
    return updated;
  }

  private long backfillCompletedPurchases() {
    Query missing =
        new Query(
            new Criteria()
                .andOperator(
                    Criteria.where("status").is(PurchaseStatus.COMPLETED),
                    new Criteria()
                        .orOperator(
                            Criteria.where("txnId").is(null),
                            Criteria.where("txnId").is(""),
                            Criteria.where("txnId").exists(false))));
    List<Purchase> rows = mongoTemplate.find(missing, Purchase.class);
    long updated = 0;
    for (Purchase row : rows) {
      if (!StringUtils.hasText(row.getId())) {
        continue;
      }
      if (dryRun) {
        updated++;
        continue;
      }
      Update update = new Update().set("txnId", TxnIdGenerator.newId());
      mongoTemplate.updateFirst(
          Query.query(Criteria.where("_id").is(row.getId())), update, Purchase.class);
      updated++;
    }
    log.info("[txn-id-backfill] purchases(COMPLETED): {} row(s)", updated);
    return updated;
  }

  private static String readId(Object row) {
    if (row instanceof VendorPurchaseInvoice inv) {
      return inv.getId();
    }
    if (row instanceof VendorPurchaseReturn ret) {
      return ret.getId();
    }
    if (row instanceof Refund refund) {
      return refund.getId();
    }
    if (row instanceof CreditEntry entry) {
      return entry.getId();
    }
    if (row instanceof Purchase purchase) {
      return purchase.getId();
    }
    return null;
  }
}
