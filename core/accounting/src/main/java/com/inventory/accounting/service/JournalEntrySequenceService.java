package com.inventory.accounting.service;

import com.inventory.accounting.domain.model.AccountingSequence;
import lombok.RequiredArgsConstructor;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

/**
 * Atomic per-shop journal entry number generator. Yields strings like {@code JE-000001}; uses
 * Mongo {@code findAndModify} for safe concurrency without distributed locks.
 */
@Service
@RequiredArgsConstructor
public class JournalEntrySequenceService {

  private static final String PREFIX = "JE-";
  private static final int PAD = 6;
  private static final String SEQUENCE_SUFFIX = ":JE";

  private final MongoTemplate mongoTemplate;

  public String nextEntryNo(String shopId) {
    if (shopId == null || shopId.isBlank()) {
      throw new IllegalArgumentException("shopId is required");
    }
    String key = shopId + SEQUENCE_SUFFIX;
    Query q = new Query(Criteria.where("_id").is(key));
    Update u = new Update().inc("seq", 1);
    FindAndModifyOptions opts = FindAndModifyOptions.options().upsert(true).returnNew(true);
    AccountingSequence seq = mongoTemplate.findAndModify(q, u, opts, AccountingSequence.class);
    long n = seq != null ? seq.getSeq() : 1L;
    return PREFIX + String.format("%0" + PAD + "d", n);
  }
}
