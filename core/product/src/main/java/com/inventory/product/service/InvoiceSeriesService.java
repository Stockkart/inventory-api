package com.inventory.product.service;

import com.inventory.common.exception.ResourceNotFoundException;
import com.inventory.common.exception.ValidationException;
import com.inventory.product.domain.model.InvoiceSequence;
import com.inventory.product.domain.model.InvoiceSequenceSource;
import com.inventory.product.domain.repository.PurchaseRepository;
import com.inventory.product.domain.repository.ShopRepository;
import com.inventory.product.rest.dto.request.UpdateInvoiceSeriesRequest;
import com.inventory.product.rest.dto.response.InvoiceSeriesResponse;
import com.inventory.product.utils.FinancialYear;
import com.inventory.product.utils.InvoiceNumberParser;
import com.inventory.product.validation.ShopValidator;
import com.inventory.user.service.UserShopMembershipService;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
@Slf4j
public class InvoiceSeriesService {

  private static final Pattern INV_SUFFIX = Pattern.compile("^INV-(\\d+)$", Pattern.CASE_INSENSITIVE);

  @Autowired
  private InvoiceSequenceService invoiceSequenceService;

  @Autowired
  private MongoTemplate mongoTemplate;

  @Autowired
  private ShopRepository shopRepository;

  @Autowired
  private PurchaseRepository purchaseRepository;

  @Autowired
  private UserShopMembershipService membershipService;

  @Autowired
  private ShopValidator shopValidator;

  private Clock clock = Clock.system(FinancialYear.IST);

  @Autowired(required = false)
  void setClock(Clock clock) {
    if (clock != null) {
      this.clock = clock;
    }
  }

  public InvoiceSeriesResponse getSeries(String shopId, String userId) {
    shopValidator.validateShopAccess(membershipService.hasAccess(userId, shopId));
    ensureShopExists(shopId);
    return toResponse(shopId, invoiceSequenceService.findRegular(shopId));
  }

  public InvoiceSeriesResponse updateSeries(
      String shopId, String userId, UpdateInvoiceSeriesRequest request) {
    shopValidator.validateShopAccess(membershipService.hasAccess(userId, shopId));
    ensureShopExists(shopId);
    if (request == null) {
      throw new ValidationException("Request body is required");
    }

    InvoiceSequence existing = invoiceSequenceService.findRegular(shopId);
    if (isLocked(shopId, existing)) {
      throw new ValidationException(
          "Invoice numbering is locked after the first regular invoice was issued");
    }

    boolean useDefault = Boolean.TRUE.equals(request.getUseStockKartDefault());
    boolean hasLast = StringUtils.hasText(request.getLastInvoiceNo());
    if (useDefault == hasLast) {
      throw new ValidationException(
          "Provide either lastInvoiceNo or useStockKartDefault=true (not both)");
    }

    String currentFy = FinancialYear.currentLabel(clock);
    InvoiceSequence doc = existing != null ? existing : new InvoiceSequence();
    doc.setShopId(shopId);

    if (useDefault) {
      doc.setPrefix(InvoiceSequenceService.DEFAULT_REGULAR_PREFIX);
      doc.setPadLength(InvoiceSequenceService.DEFAULT_PAD_LENGTH);
      doc.setSource(InvoiceSequenceSource.STOCKKART);
      doc.setFyLabel(currentFy);
      doc.setSeq(0L);
      doc.setLockedAt(null);
    } else {
      InvoiceNumberParser.Parsed parsed;
      try {
        parsed = InvoiceNumberParser.parse(request.getLastInvoiceNo());
      } catch (IllegalArgumentException ex) {
        throw new ValidationException(ex.getMessage());
      }
      doc.setPrefix(parsed.prefix());
      doc.setPadLength(parsed.padLength());
      doc.setSource(InvoiceSequenceSource.MIGRATED);
      doc.setFyLabel(currentFy);
      doc.setSeq(parsed.counter());
      doc.setLockedAt(null);
    }

    invoiceSequenceService.saveRegular(doc);
    log.info(
        "Updated invoice series for shop {}: source={}, prefix={}, seq={}",
        shopId,
        doc.getSource(),
        doc.getPrefix(),
        doc.getSeq());
    return toResponse(shopId, doc);
  }

  /**
   * Idempotent backfill: adopt StockKart defaults + current FY on legacy REGULAR docs that lack
   * {@code fyLabel}, continuing from existing {@code seq} / max INV-* purchase.
   */
  public int backfillExistingShops() {
    String currentFy = FinancialYear.currentLabel(clock);
    Query legacy =
        new Query(
            Criteria.where("fyLabel")
                .exists(false)
                .and("_id")
                .not()
                .regex(":"));
    List<InvoiceSequence> docs = mongoTemplate.find(legacy, InvoiceSequence.class);
    int updated = 0;
    for (InvoiceSequence doc : docs) {
      String shopId = doc.getShopId();
      long maxFromPurchases = maxInvSuffix(shopId);
      long seq = Math.max(doc.getSeq(), maxFromPurchases);
      doc.setPrefix(InvoiceSequenceService.DEFAULT_REGULAR_PREFIX);
      doc.setPadLength(InvoiceSequenceService.DEFAULT_PAD_LENGTH);
      doc.setSource(InvoiceSequenceSource.STOCKKART);
      doc.setFyLabel(currentFy);
      doc.setSeq(seq);
      if (seq > 0) {
        doc.setLockedAt(Instant.now(clock));
      }
      mongoTemplate.save(doc);
      updated++;
      log.info(
          "Backfilled invoice sequence shopId={} fy={} seq={}", shopId, currentFy, seq);
    }
    return updated;
  }

  private long maxInvSuffix(String shopId) {
    List<String> invoiceNos = purchaseRepository.findRegularInvoiceNosByShopId(shopId);
    long max = 0L;
    for (String invoiceNo : invoiceNos) {
      if (!StringUtils.hasText(invoiceNo)) {
        continue;
      }
      Matcher matcher = INV_SUFFIX.matcher(invoiceNo.trim());
      if (matcher.matches()) {
        try {
          max = Math.max(max, Long.parseLong(matcher.group(1)));
        } catch (NumberFormatException ignored) {
          // skip
        }
      }
    }
    return max;
  }

  private InvoiceSeriesResponse toResponse(String shopId, InvoiceSequence doc) {
    String currentFy = FinancialYear.currentLabel(clock);
    String prefix = InvoiceSequenceService.DEFAULT_REGULAR_PREFIX;
    int pad = InvoiceSequenceService.DEFAULT_PAD_LENGTH;
    String source = InvoiceSequenceSource.STOCKKART;
    Long lastCounter = null;
    boolean locked = false;

    if (doc != null) {
      prefix = InvoiceSequenceService.resolvePrefix(doc);
      pad = InvoiceSequenceService.resolvePad(doc);
      if (StringUtils.hasText(doc.getSource())) {
        source = doc.getSource();
      }
      lastCounter = doc.getSeq();
      locked = isLocked(shopId, doc);
    }

    String nextPreview = invoiceSequenceService.peekNextRegularInvoiceNo(shopId);
    return InvoiceSeriesResponse.builder()
        .shopId(shopId)
        .prefix(prefix)
        .padLength(pad)
        .source(source)
        .currentFy(currentFy)
        .nextPreview(nextPreview)
        .locked(locked)
        .lastCounter(lastCounter)
        .build();
  }

  private boolean isLocked(String shopId, InvoiceSequence doc) {
    if (doc != null && doc.getLockedAt() != null) {
      return true;
    }
    return purchaseRepository.existsCompletedRegularInvoice(shopId);
  }

  private void ensureShopExists(String shopId) {
    if (!shopRepository.existsById(shopId)) {
      throw new ResourceNotFoundException("Shop", "shopId", shopId);
    }
  }
}
