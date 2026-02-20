package com.inventory.product.service;

import com.inventory.product.domain.model.InvoiceSequence;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

/**
 * Generates short, incremental invoice numbers per shop (e.g. INV-00001, INV-00002).
 * Uses MongoDB findAndModify for atomic sequence increment.
 */
@Service
@Slf4j
public class InvoiceSequenceService {

  private static final int SEQ_PAD_LENGTH = 5; // INV-99999 max per shop; increase if needed
  private static final String PREFIX = "INV-";

  private final MongoTemplate mongoTemplate;

  public InvoiceSequenceService(MongoTemplate mongoTemplate) {
    this.mongoTemplate = mongoTemplate;
  }

  /**
   * Returns the next invoice number for the shop. Format: INV-00001, INV-00002, ...
   * Thread-safe and unique per shop.
   */
  public String getNextInvoiceNo(String shopId) {
    if (shopId == null || shopId.isBlank()) {
      throw new IllegalArgumentException("shopId is required");
    }
    log.info("Generating next invoice number for shop: {}", shopId);
    Query query = new Query(Criteria.where("_id").is(shopId));
    Update update = new Update().inc("seq", 1);
    FindAndModifyOptions options = FindAndModifyOptions.options()
        .upsert(true)
        .returnNew(true);
    InvoiceSequence seq = mongoTemplate.findAndModify(
        query,
        update,
        options,
        InvoiceSequence.class
    );
    long next = seq != null ? seq.getSeq() : 1L;
    return PREFIX + String.format("%0" + SEQ_PAD_LENGTH + "d", next);
  }
}
