package com.inventory.product.service.api;

import com.inventory.pluginengine.kot.CafeCartPunchPort;
import com.inventory.product.domain.model.CafeKotPunchStatus;
import com.inventory.product.service.vertical.CafeCartPuncher;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

/**
 * {@link CafeCartPunchPort} for {@code plugins/cafe}: the cart reads and writes of a KOT punch,
 * kept here because {@code purchases} belongs to this module.
 *
 * <p>The cart is read as raw BSON, not through {@code PurchaseRepository}: the punch deltas are
 * written by an aggregation pipeline, so a quantity may come back as any numeric width, and it is
 * read here exactly as the plugin used to read it.
 */
@Service
public class CafeCartPunchPortImpl implements CafeCartPunchPort {

  private static final String PURCHASES = "purchases";
  private static final String PUNCHES = "cafeKotPunches";
  private static final String COMPLETE = CafeKotPunchStatus.COMPLETE.name();

  private final MongoTemplate mongoTemplate;
  private final CafeCartPuncher puncher;

  public CafeCartPunchPortImpl(MongoTemplate mongoTemplate, CafeCartPuncher puncher) {
    this.mongoTemplate = mongoTemplate;
    this.puncher = puncher;
  }

  @Override
  public boolean claimAndReconcile(
      String shopId, String purchaseId, String punchId, String idempotencyKey, String userId) {
    return puncher.claimAndReconcile(shopId, purchaseId, punchId, idempotencyKey, userId).isPresent();
  }

  @Override
  public Optional<Cart> findCart(String shopId, String purchaseId) {
    Document purchase =
        mongoTemplate.findOne(
            Query.query(Criteria.where("_id").is(purchaseId).and("shopId").is(shopId)),
            Document.class,
            PURCHASES);
    return Optional.ofNullable(purchase).map(CafeCartPunchPortImpl::toCart);
  }

  /** Finishes the punch in place. Scoped by shop, and matched by punchId so {@code $} is that punch. */
  @Override
  public void markPunchComplete(
      String shopId, String purchaseId, String punchId, List<String> kotIds) {
    Query query =
        Query.query(
            Criteria.where("_id")
                .is(purchaseId)
                .and("shopId")
                .is(shopId)
                .and(PUNCHES + ".punchId")
                .is(punchId));
    Update update =
        new Update().set(PUNCHES + ".$.status", COMPLETE).set(PUNCHES + ".$.kotIds", kotIds);
    mongoTemplate.updateFirst(query, update, PURCHASES);
  }

  static Cart toCart(Document purchase) {
    List<Punch> punches = new ArrayList<>();
    for (Document punch : documents(purchase.get(PUNCHES))) {
      punches.add(toPunch(punch));
    }
    return new Cart(
        String.valueOf(purchase.get("_id")),
        purchase.getString("tableLabel"),
        purchase.getString("tokenNo"),
        punches);
  }

  private static Punch toPunch(Document punch) {
    List<Delta> deltas = new ArrayList<>();
    for (Document delta : documents(punch.get("deltas"))) {
      deltas.add(
          new Delta(
              delta.getString("sellableRef"),
              delta.getString("name"),
              delta.getString("department"),
              delta.getString("note"),
              intValue(delta.get("quantity"))));
    }
    return new Punch(
        punch.getString("punchId"),
        punch.getString("idempotencyKey"),
        COMPLETE.equals(punch.getString("status")),
        strings(punch.get("kotIds")),
        deltas);
  }

  @SuppressWarnings("unchecked")
  private static List<Document> documents(Object raw) {
    return raw == null ? List.of() : (List<Document>) raw;
  }

  @SuppressWarnings("unchecked")
  private static List<String> strings(Object raw) {
    return raw == null ? List.of() : (List<String>) raw;
  }

  /** Mongo returns whatever numeric width the pipeline produced; an Integer is not guaranteed. */
  private static int intValue(Object raw) {
    if (raw instanceof Number number) {
      return number.intValue();
    }
    // $subtract always yields a number, so this is unreachable today; but a malformed delta
    // quantity must fail loudly rather than be silently treated as zero and dropped.
    throw new IllegalStateException("Cafe KOT delta quantity is not a number: " + raw);
  }
}
