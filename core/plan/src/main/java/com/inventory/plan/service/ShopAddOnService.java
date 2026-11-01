package com.inventory.plan.service;

import com.inventory.plan.domain.model.AddOn;
import com.inventory.plan.domain.model.AddOnBillingType;
import com.inventory.plan.domain.model.AddOnGrantType;
import com.inventory.plan.domain.model.OrderLine;
import com.inventory.plan.domain.model.PlanPaymentOrder;
import com.inventory.plan.domain.model.ShopAddOn;
import com.inventory.plan.domain.model.ShopAddOnSource;
import com.inventory.plan.domain.repository.ShopAddOnRepository;
import com.inventory.plan.mapper.AddOnMapper;
import com.inventory.plan.rest.dto.response.ShopAddOnResponse;
import com.inventory.plan.utils.constants.PricingConstants;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.bson.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

/**
 * Add-ons a shop holds: granting them (once per order), reading the live ones for entitlements,
 * and spending purchased OCR credits.
 */
@Service
@Slf4j
public class ShopAddOnService {

  @Autowired
  private ShopAddOnRepository shopAddOnRepository;

  @Autowired
  private AddOnCatalogueService catalogue;

  @Autowired
  private MongoTemplate mongoTemplate;

  @Autowired
  private EntitlementService entitlementService;

  @Autowired
  private AddOnMapper addOnMapper;

  Clock clock = Clock.systemUTC();

  /** Add-ons that have not expired, OCR credits included. */
  public List<ShopAddOn> live(String shopId, Instant now) {
    return shopAddOnRepository.findByShopId(shopId).stream()
        .filter(addOn -> addOn.isLive(now))
        .toList();
  }

  /** Every add-on the shop has held, newest first. */
  public List<ShopAddOnResponse> describe(String shopId) {
    Instant now = clock.instant();
    return shopAddOnRepository.findByShopId(shopId).stream()
        .sorted(Comparator.comparing(ShopAddOn::getPurchasedAt, Comparator.nullsLast(Comparator.reverseOrder())))
        .map(addOn -> addOnMapper.toShopAddOnResponse(addOn, now))
        .toList();
  }

  /**
   * Grants every add-on line of a paid order. Annual add-ons end with {@code termEndsAt}; OCR
   * credits never expire. Safe to repeat: the (order, add-on) unique index turns a second grant
   * into a no-op.
   */
  public void grantForOrder(PlanPaymentOrder order, Instant termEndsAt) {
    List<OrderLine> lines = order.getItems() == null ? List.of() : order.getItems().stream()
        .filter(line -> PricingConstants.ITEM_TYPE_ADDON.equals(line.getType())
            || PricingConstants.ITEM_TYPE_OCR_TOPUP.equals(line.getType()))
        .toList();
    if (lines.isEmpty()) {
      return;
    }
    Map<String, AddOn> addOns = catalogue.byCode(lines.stream().map(OrderLine::getCode).toList());
    Instant now = clock.instant();
    for (OrderLine line : lines) {
      AddOn addOn = addOns.get(line.getCode());
      if (addOn == null) {
        throw new IllegalStateException("Add-on " + line.getCode() + " on order " + order.getId() + " no longer exists");
      }
      ShopAddOn grant = newGrant(order.getShopId(), addOn, line.getQuantity(), now, termEndsAt);
      grant.setSourceOrderId(order.getId());
      grant.setSource(ShopAddOnSource.ORDER);
      insertOnce(grant);
    }
    entitlementService.invalidate(order.getShopId());
  }

  /**
   * Ends every add-on a refunded order granted, now. Unused purchased OCR credits are forfeited;
   * credits already spent are not recovered. Safe to repeat.
   */
  public void revokeForOrder(PlanPaymentOrder order) {
    Instant now = clock.instant();
    Criteria granted = Criteria.where("sourceOrderId").is(order.getId()).and("revokedAt").is(null);
    mongoTemplate.updateMulti(new Query(Criteria.where("sourceOrderId").is(order.getId()).and("revokedAt").is(null)
            .and("grantType").is(AddOnGrantType.OCR_CREDITS)),
        new Update().set("remainingCredits", 0), ShopAddOn.class);
    long revoked = mongoTemplate.updateMulti(new Query(granted),
        new Update().set("revokedAt", now).set("expiresAt", now), ShopAddOn.class).getModifiedCount();
    if (revoked > 0) {
      log.info("Revoked {} add-on(s) of refunded order {} for shop {}", revoked, order.getId(), order.getShopId());
    }
    entitlementService.invalidate(order.getShopId());
  }

  /** Platform-admin grant outside an order. {@code expiresAt} is ignored for OCR credits. */
  public ShopAddOn grantByAdmin(String shopId, AddOn addOn, int quantity, Instant expiresAt,
      String actorUserId, String note) {
    ShopAddOn grant = newGrant(shopId, addOn, quantity, clock.instant(), expiresAt);
    grant.setSource(ShopAddOnSource.ADMIN);
    grant.setGrantedByUserId(actorUserId);
    grant.setNote(note);
    ShopAddOn saved = mongoTemplate.insert(grant);
    entitlementService.invalidate(shopId);
    return saved;
  }

  /** Purchased OCR credits left across all top-ups. */
  public int ocrCreditsRemaining(String shopId) {
    Aggregation aggregation = Aggregation.newAggregation(
        Aggregation.match(ocrCreditsWithBalance(shopId, 1)),
        Aggregation.group().sum("remainingCredits").as("total"));
    Document result = mongoTemplate.aggregate(aggregation, ShopAddOn.class, Document.class).getUniqueMappedResult();
    return result == null ? 0 : ((Number) result.get("total")).intValue();
  }

  /**
   * Spends one purchased OCR credit, oldest top-up first. Conditional, so parallel scans cannot
   * take a balance below zero (r4.14). Returns false when no credit is left.
   */
  public boolean consumeOcrCredit(String shopId) {
    Query query = new Query(ocrCreditsWithBalance(shopId, 1)).with(Sort.by(Sort.Direction.ASC, "purchasedAt"));
    ShopAddOn spent = mongoTemplate.findAndModify(query, new Update().inc("remainingCredits", -1),
        FindAndModifyOptions.options().returnNew(true), ShopAddOn.class);
    return spent != null;
  }

  private void insertOnce(ShopAddOn grant) {
    try {
      mongoTemplate.insert(grant);
      log.info("Granted add-on {} x{} to shop {} for order {}",
          grant.getAddOnCode(), grant.getQuantity(), grant.getShopId(), grant.getSourceOrderId());
    } catch (DuplicateKeyException e) {
      log.info("Add-on {} for order {} already granted", grant.getAddOnCode(), grant.getSourceOrderId());
    }
  }

  private static ShopAddOn newGrant(String shopId, AddOn addOn, int quantity, Instant now, Instant termEndsAt) {
    int perUnit = addOn.getGrantsQuantity() != null ? addOn.getGrantsQuantity() : 1;
    int granted = perUnit * quantity;
    boolean credits = addOn.getGrantType() == AddOnGrantType.OCR_CREDITS;
    return ShopAddOn.builder()
        .shopId(shopId)
        .addOnCode(addOn.getCode())
        .name(addOn.getName())
        .grantType(addOn.getGrantType())
        .grantsFeature(addOn.getGrantsFeature())
        .quantity(quantity)
        .grantedQuantity(granted)
        .remainingCredits(credits ? granted : null)
        .purchasedAt(now)
        .expiresAt(credits || addOn.getBillingType() == AddOnBillingType.ONE_TIME ? null : termEndsAt)
        .createdAt(now)
        .build();
  }

  private static Criteria ocrCreditsWithBalance(String shopId, int atLeast) {
    return Criteria.where("shopId").is(shopId)
        .and("grantType").is(AddOnGrantType.OCR_CREDITS)
        .and("remainingCredits").gte(atLeast);
  }
}
