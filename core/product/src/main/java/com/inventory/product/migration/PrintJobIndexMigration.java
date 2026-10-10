package com.inventory.product.migration;

import com.inventory.product.domain.model.PrintJob;
import com.inventory.product.domain.model.enums.PrintJobStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.index.IndexOperations;
import org.springframework.data.mongodb.core.index.PartialIndexFilter;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.stereotype.Component;

/**
 * Idempotent: creates the {@code print_jobs} indexes. Automatic index creation is off.
 *
 * <p>The in-flight index is unique only while a job is PENDING or SUBMITTED, so two clicks
 * cannot put the same document on the printer twice at once, while a deliberate reprint after
 * it printed, failed or expired is still allowed. A sparse index would not do this: it would
 * make each document printable once for all time.
 */
@Component
@Slf4j
public class PrintJobIndexMigration {

  static final String IN_FLIGHT_INDEX = "shop_document_in_flight_unique";
  static final String SHOP_CREATED_INDEX = "shop_created_at";

  @Autowired private MongoTemplate mongoTemplate;

  @EventListener(ApplicationReadyEvent.class)
  @Order(5)
  public void run() {
    try {
      IndexOperations ops = mongoTemplate.indexOps(PrintJob.class);
      ops.ensureIndex(
          new Index()
              .on("shopId", Sort.Direction.ASC)
              .on("source", Sort.Direction.ASC)
              .on("documentId", Sort.Direction.ASC)
              .unique()
              .partial(
                  PartialIndexFilter.of(
                      Criteria.where("status")
                          .in(PrintJobStatus.IN_FLIGHT.stream().map(Enum::name).toList())))
              .named(IN_FLIGHT_INDEX));
      ops.ensureIndex(
          new Index()
              .on("shopId", Sort.Direction.ASC)
              .on("createdAt", Sort.Direction.DESC)
              .named(SHOP_CREATED_INDEX));
    } catch (RuntimeException e) {
      log.error("[print-job-index] failed to create print job indexes: {}", e.getMessage(), e);
    }
  }
}
