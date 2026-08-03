package com.inventory.product.service;

import com.inventory.product.domain.model.InvoiceSequence;
import com.inventory.product.domain.model.InvoiceSequenceSource;
import com.inventory.product.utils.FinancialYear;
import com.inventory.product.utils.InvoiceNumberParser;
import java.time.Clock;
import java.time.Instant;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Generates invoice / credit-note numbers. REGULAR series is FY-scoped with configurable prefix;
 * BASIC / CN / VCN remain lifetime counters with hardcoded prefixes.
 */
@Service
@Slf4j
public class InvoiceSequenceService {

  public static final String DEFAULT_REGULAR_PREFIX = "INV-";
  public static final int DEFAULT_PAD_LENGTH = 5;

  private static final int MAX_ATTEMPTS = 8;
  private static final String BASIC_PREFIX = "BSC-";
  private static final String BASIC_SEQUENCE_SUFFIX = ":BASIC";
  private static final String CREDIT_NOTE_PREFIX = "CN-";
  private static final String CREDIT_NOTE_SEQUENCE_SUFFIX = ":CN";
  private static final String VENDOR_CN_PREFIX = "VCN-";
  private static final String VENDOR_CN_SEQUENCE_SUFFIX = ":VCN";

  private final MongoTemplate mongoTemplate;
  private final Clock clock;

  @Autowired
  public InvoiceSequenceService(MongoTemplate mongoTemplate) {
    this(mongoTemplate, Clock.system(FinancialYear.IST));
  }

  /** Test-only: inject a fixed clock for FY boundary coverage. */
  InvoiceSequenceService(MongoTemplate mongoTemplate, Clock clock) {
    this.mongoTemplate = mongoTemplate;
    this.clock = clock;
  }

  /**
   * Next REGULAR invoice number for the shop (FY-aware, configurable prefix).
   */
  public String getNextInvoiceNo(String shopId) {
    if (!StringUtils.hasText(shopId)) {
      throw new IllegalArgumentException("shopId is required");
    }
    InvoiceSequence doc = nextRegularSequence(shopId);
    return formatRegular(doc);
  }

  public String getNextBasicInvoiceNo(String shopId) {
    String sequenceKey = shopId + BASIC_SEQUENCE_SUFFIX;
    return getNextFixedPrefixSequence(shopId, sequenceKey, BASIC_PREFIX);
  }

  public String getNextCreditNoteNo(String shopId) {
    String sequenceKey = shopId + CREDIT_NOTE_SEQUENCE_SUFFIX;
    return getNextFixedPrefixSequence(shopId, sequenceKey, CREDIT_NOTE_PREFIX);
  }

  public String getNextVendorCreditNoteNo(String shopId) {
    String sequenceKey = shopId + VENDOR_CN_SEQUENCE_SUFFIX;
    return getNextFixedPrefixSequence(shopId, sequenceKey, VENDOR_CN_PREFIX);
  }

  /** Peek next REGULAR number without incrementing. */
  public String peekNextRegularInvoiceNo(String shopId) {
    if (!StringUtils.hasText(shopId)) {
      throw new IllegalArgumentException("shopId is required");
    }
    String currentFy = FinancialYear.currentLabel(clock);
    InvoiceSequence doc = mongoTemplate.findById(shopId, InvoiceSequence.class);
    if (doc == null) {
      return InvoiceNumberParser.format(DEFAULT_REGULAR_PREFIX, DEFAULT_PAD_LENGTH, 1L);
    }
    String prefix = resolvePrefix(doc);
    int pad = resolvePad(doc);
    if (!StringUtils.hasText(doc.getFyLabel()) || currentFy.equals(doc.getFyLabel())) {
      return InvoiceNumberParser.format(prefix, pad, doc.getSeq() + 1);
    }
    return InvoiceNumberParser.format(prefix, pad, 1L);
  }

  public InvoiceSequence findRegular(String shopId) {
    return mongoTemplate.findById(shopId, InvoiceSequence.class);
  }

  public void saveRegular(InvoiceSequence sequence) {
    mongoTemplate.save(sequence);
  }

  private InvoiceSequence nextRegularSequence(String shopId) {
    String currentFy = FinancialYear.currentLabel(clock);
    for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
      InvoiceSequence existing = mongoTemplate.findById(shopId, InvoiceSequence.class);

      if (existing == null) {
        InvoiceSequence created = upsertFirstRegular(shopId, currentFy);
        if (created != null) {
          return lockIfNeeded(created);
        }
        continue;
      }

      if (!StringUtils.hasText(existing.getFyLabel())) {
        adoptLegacyRegular(shopId, existing, currentFy);
        continue;
      }

      if (!currentFy.equals(existing.getFyLabel())) {
        InvoiceSequence rolled = rolloverRegular(shopId, existing.getFyLabel(), currentFy);
        if (rolled != null) {
          return lockIfNeeded(rolled);
        }
        continue;
      }

      InvoiceSequence incremented = incrementSameFy(shopId, currentFy);
      if (incremented != null) {
        return lockIfNeeded(incremented);
      }
    }
    throw new IllegalStateException(
        "Failed to allocate REGULAR invoice sequence for shop " + shopId);
  }

  private InvoiceSequence upsertFirstRegular(String shopId, String currentFy) {
    Query query = new Query(Criteria.where("_id").is(shopId));
    Update update =
        new Update()
            .setOnInsert("prefix", DEFAULT_REGULAR_PREFIX)
            .setOnInsert("padLength", DEFAULT_PAD_LENGTH)
            .setOnInsert("source", InvoiceSequenceSource.STOCKKART)
            .setOnInsert("fyLabel", currentFy)
            .inc("seq", 1);
    return mongoTemplate.findAndModify(
        query,
        update,
        FindAndModifyOptions.options().upsert(true).returnNew(true),
        InvoiceSequence.class);
  }

  private void adoptLegacyRegular(String shopId, InvoiceSequence existing, String currentFy) {
    Update update =
        new Update()
            .set("fyLabel", currentFy)
            .set(
                "prefix",
                StringUtils.hasText(existing.getPrefix())
                    ? existing.getPrefix()
                    : DEFAULT_REGULAR_PREFIX)
            .set(
                "padLength",
                existing.getPadLength() != null && existing.getPadLength() > 0
                    ? existing.getPadLength()
                    : DEFAULT_PAD_LENGTH)
            .set(
                "source",
                StringUtils.hasText(existing.getSource())
                    ? existing.getSource()
                    : InvoiceSequenceSource.STOCKKART);
    if (existing.getSeq() > 0 && existing.getLockedAt() == null) {
      update.set("lockedAt", Instant.now(clock));
    }
    mongoTemplate.updateFirst(
        new Query(Criteria.where("_id").is(shopId).and("fyLabel").exists(false)),
        update,
        InvoiceSequence.class);
  }

  private InvoiceSequence rolloverRegular(String shopId, String oldFy, String currentFy) {
    Query query = new Query(Criteria.where("_id").is(shopId).and("fyLabel").is(oldFy));
    Update update = new Update().set("fyLabel", currentFy).set("seq", 1L);
    return mongoTemplate.findAndModify(
        query, update, FindAndModifyOptions.options().returnNew(true), InvoiceSequence.class);
  }

  private InvoiceSequence incrementSameFy(String shopId, String currentFy) {
    Query query = new Query(Criteria.where("_id").is(shopId).and("fyLabel").is(currentFy));
    Update update = new Update().inc("seq", 1);
    return mongoTemplate.findAndModify(
        query, update, FindAndModifyOptions.options().returnNew(true), InvoiceSequence.class);
  }

  private InvoiceSequence lockIfNeeded(InvoiceSequence doc) {
    if (doc.getLockedAt() == null) {
      Instant now = Instant.now(clock);
      mongoTemplate.updateFirst(
          new Query(Criteria.where("_id").is(doc.getShopId()).and("lockedAt").is(null)),
          new Update().set("lockedAt", now),
          InvoiceSequence.class);
      doc.setLockedAt(now);
    }
    if (!StringUtils.hasText(doc.getPrefix())) {
      doc.setPrefix(DEFAULT_REGULAR_PREFIX);
    }
    if (doc.getPadLength() == null || doc.getPadLength() <= 0) {
      doc.setPadLength(DEFAULT_PAD_LENGTH);
    }
    if (!StringUtils.hasText(doc.getSource())) {
      doc.setSource(InvoiceSequenceSource.STOCKKART);
    }
    return doc;
  }

  private String getNextFixedPrefixSequence(String shopId, String sequenceKey, String prefix) {
    if (!StringUtils.hasText(shopId)) {
      throw new IllegalArgumentException("shopId is required");
    }
    log.debug("Generating next fixed-prefix sequence for shop: {}, key: {}", shopId, sequenceKey);
    Query query = new Query(Criteria.where("_id").is(sequenceKey));
    Update update = new Update().inc("seq", 1);
    InvoiceSequence seq =
        mongoTemplate.findAndModify(
            query,
            update,
            FindAndModifyOptions.options().upsert(true).returnNew(true),
            InvoiceSequence.class);
    long next = seq != null ? seq.getSeq() : 1L;
    return InvoiceNumberParser.format(prefix, DEFAULT_PAD_LENGTH, next);
  }

  static String formatRegular(InvoiceSequence doc) {
    return InvoiceNumberParser.format(resolvePrefix(doc), resolvePad(doc), doc.getSeq());
  }

  static String resolvePrefix(InvoiceSequence doc) {
    return StringUtils.hasText(doc.getPrefix()) ? doc.getPrefix() : DEFAULT_REGULAR_PREFIX;
  }

  static int resolvePad(InvoiceSequence doc) {
    return doc.getPadLength() != null && doc.getPadLength() > 0
        ? doc.getPadLength()
        : DEFAULT_PAD_LENGTH;
  }
}
