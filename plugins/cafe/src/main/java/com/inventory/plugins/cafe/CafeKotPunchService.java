package com.inventory.plugins.cafe;

import com.inventory.common.exception.ResourceNotFoundException;
import com.inventory.common.exception.ValidationException;
import com.inventory.pluginengine.menu.MenuDepartments;
import com.inventory.plugins.cafe.domain.CafeKot;
import com.inventory.plugins.cafe.domain.CafeKotKind;
import com.inventory.plugins.cafe.domain.CafeKotLine;
import com.inventory.plugins.cafe.domain.CafeKotRepository;
import com.inventory.plugins.cafe.domain.CafeKotStatus;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Turns a reconciled cafe cart into kitchen tickets, and makes an interrupted punch recoverable.
 *
 * <p>The flow, and no other:
 *
 * <pre>
 *   read the Purchase
 *     punch with this key is COMPLETE              -&gt; return its tickets; reconcile nothing
 *     punch with this key is PENDING_KOT_CREATION  -&gt; create only the MISSING tickets, then COMPLETE
 *     no punch with this key                       -&gt; claimAndReconcile, then tickets, then COMPLETE
 * </pre>
 *
 * <p><b>A replay must never reconcile again.</b> The cart has already advanced: its lines'
 * {@code kotPunchedQuantity} now equals their {@code baseQuantity}. A second reconcile would
 * compute every delta as zero and silently drop the tickets the kitchen is owed — food cooking
 * that nobody billed, with nothing in the system aware of it. So a retry reads its deltas off the
 * punch record that the first attempt made durable, never off the lines.
 *
 * <p><b>Ticket creation is idempotent by construction.</b> A KOT's {@code _id} is
 * {@code {punchId}:{department}:{kind}}. There is no {@code MongoTransactionManager} in this
 * codebase, so a crash between the KITCHEN ticket and the BAR ticket is not preventable — it is
 * merely made harmless: the recovery writes BAR alone, and rewriting KITCHEN would have replaced
 * the same document rather than duplicating it. A ticket number allocated for a ticket that turns
 * out to exist is a burnt sequence number, which is cheaper than a duplicate ticket.
 *
 * <p>The punch record's Java model ({@code CafeKotPunch} and friends) lives in {@code core/product},
 * which this module does not depend on. As in {@link CafeCartPuncher}, the document shape is read
 * directly off {@link Document} and the two status values are string literals here.
 */
@Service
@Slf4j
public class CafeKotPunchService {

  private static final String PURCHASES = "purchases";
  private static final String PUNCHES = "cafeKotPunches";

  /** {@code CafeKotPunchStatus.PENDING_KOT_CREATION}; a literal — cafe does not see core. */
  private static final String PENDING_KOT_CREATION = "PENDING_KOT_CREATION";

  /** {@code CafeKotPunchStatus.COMPLETE}. */
  private static final String COMPLETE = "COMPLETE";

  private final MongoTemplate mongoTemplate;
  private final CafeCartPuncher puncher;
  private final CafeKotRepository kotRepository;
  private final CafeSequenceService sequenceService;

  public CafeKotPunchService(
      MongoTemplate mongoTemplate,
      CafeCartPuncher puncher,
      CafeKotRepository kotRepository,
      CafeSequenceService sequenceService) {
    this.mongoTemplate = mongoTemplate;
    this.puncher = puncher;
    this.kotRepository = kotRepository;
    this.sequenceService = sequenceService;
  }

  /**
   * Punches the cart, or finishes a punch that an earlier attempt left half-done.
   *
   * @return the tickets this punch stands for — every one of them on a completed punch, and on a
   *     recovery every one that could be read back plus the ones written here.
   */
  public List<CafeKot> punch(String shopId, String userId, String purchaseId, String idempotencyKey) {
    if (!StringUtils.hasText(shopId) || !StringUtils.hasText(purchaseId)) {
      throw new ValidationException("shopId and purchaseId are required to punch a cart");
    }
    if (!StringUtils.hasText(idempotencyKey)) {
      throw new ValidationException("Idempotency-Key is required when punching a cart");
    }

    Document purchase = requirePurchase(shopId, purchaseId);
    Document punch = findPunch(purchase, idempotencyKey);

    if (punch == null) {
      // No punch with this key yet: claim the cart and reconcile its lines, in one write.
      Optional<Document> preImage =
          puncher.claimAndReconcile(
              shopId, purchaseId, UUID.randomUUID().toString(), idempotencyKey, userId);

      // claimAndReconcile returns empty for BOTH "no such cart" and "this key is already punched",
      // and the pre-image it returns on success does not carry the deltas the pipeline computed
      // server-side. Either way the answer is on the document, so re-read it and look again.
      purchase = requirePurchase(shopId, purchaseId);
      punch = findPunch(purchase, idempotencyKey);
      if (punch == null) {
        // The cart exists, the write did not land, and no punch carries this key. Nothing was
        // reconciled, so there is nothing to recover; the caller may safely retry.
        log.warn(
            "Cafe punch of purchase {} in shop {} with key {} landed no punch (claim {})",
            purchaseId,
            shopId,
            idempotencyKey,
            preImage.isPresent() ? "matched" : "matched nothing");
        throw new ValidationException(
            "Punch " + idempotencyKey + " could not be claimed; retry the request");
      }
    }

    String punchId = punch.getString("punchId");
    if (COMPLETE.equals(punch.getString("status"))) {
      List<CafeKot> done = kotRepository.findByShopIdAndPunchId(shopId, punchId);
      log.info("Replayed complete cafe punch {} on shop {}: {} tickets", punchId, shopId, done.size());
      return done;
    }

    return finish(shopId, userId, purchaseId, purchase, punch);
  }

  /**
   * Creates the tickets this punch is missing, then marks it COMPLETE.
   *
   * <p>The deltas come from the punch record — never from a fresh read of the lines, which have
   * already advanced.
   */
  private List<CafeKot> finish(
      String shopId, String userId, String purchaseId, Document purchase, Document punch) {

    String punchId = punch.getString("punchId");
    List<CafeKot> desired = tickets(shopId, userId, purchaseId, purchase, punch);

    // `punch.kotIds` alone is not proof of what is missing: an attempt can die after saveAll (:162
    // as was) but before markComplete, leaving kotIds empty while the ticket already sits in the
    // repository. Trusting kotIds alone there would re-allocate a kotNo for a ticket that already
    // has one and saveAll would overwrite the stored document, renumbering paper a cook is already
    // holding. So the actual repository contents for this punch are the source of truth for what
    // exists; kotIds is consulted too only because it can name tickets a stale read might miss.
    List<CafeKot> existing = kotRepository.findByShopIdAndPunchId(shopId, punchId);
    Set<String> alreadyWritten = new LinkedHashSet<>(stringList(punch, "kotIds"));
    existing.forEach(k -> alreadyWritten.add(k.getId()));

    List<CafeKot> missing = desired.stream().filter(k -> !alreadyWritten.contains(k.getId())).toList();

    for (CafeKot kot : missing) {
      // Allocated only for the tickets actually written: a redrive must not renumber the kitchen's
      // existing paper, and must not burn a number for a ticket it is not printing.
      kot.setKotNo(sequenceService.allocate(shopId, LocalDate.now(), CafeSequenceSeries.KOT));
    }

    List<CafeKot> created = missing.isEmpty() ? List.of() : kotRepository.saveAll(missing);
    if (!alreadyWritten.isEmpty()) {
      log.warn(
          "Resumed cafe punch {} on shop {}: {} ticket(s) already written, {} created now",
          punchId,
          shopId,
          alreadyWritten.size(),
          created.size());
    }

    // Every ticket the punch stands for, in the order the deltas named them.
    List<String> kotIds = desired.stream().map(CafeKot::getId).toList();
    markComplete(shopId, purchaseId, punchId, kotIds);

    if (alreadyWritten.isEmpty()) {
      return created;
    }
    Map<String, CafeKot> byId = new LinkedHashMap<>();
    existing.forEach(k -> byId.put(k.getId(), k));
    created.forEach(k -> byId.put(k.getId(), k));
    return kotIds.stream().map(byId::get).filter(Objects::nonNull).toList();
  }

  /**
   * One ticket per (department, kind) the deltas call for, without numbers or a save.
   *
   * <p>Positive deltas are food to send, negative deltas food to stop; a zero delta contributes
   * nothing. The {@code department} is the one frozen onto the line at reconcile time and is used
   * as it stands — resolving it again could route a live order to a station the menu has since
   * been edited to name.
   */
  private List<CafeKot> tickets(
      String shopId, String userId, String purchaseId, Document purchase, Document punch) {

    String punchId = punch.getString("punchId");
    String businessDate = LocalDate.now().toString();
    int roundNo = roundNo(purchase, punchId);
    Instant now = Instant.now();

    Map<String, CafeKot> byKey = new LinkedHashMap<>();
    for (Document delta : deltas(punch)) {
      int quantity = intValue(delta.get("quantity"));
      if (quantity == 0) {
        continue;
      }
      CafeKotKind kind = quantity > 0 ? CafeKotKind.ISSUE : CafeKotKind.CANCEL;
      String department = MenuDepartments.resolve(delta.getString("department"));
      String id = punchId + ":" + department + ":" + kind.name();

      CafeKot kot =
          byKey.computeIfAbsent(
              id,
              k -> {
                CafeKot fresh = new CafeKot();
                fresh.setId(k);
                fresh.setShopId(shopId);
                fresh.setOrderId(purchaseId);
                fresh.setDepartment(department);
                fresh.setKind(kind);
                fresh.setRoundNo(roundNo);
                fresh.setStatus(CafeKotStatus.ISSUED);
                fresh.setPunchId(punchId);
                fresh.setBusinessDate(businessDate);
                fresh.setCreatedAt(now);
                fresh.setCreatedBy(userId);
                fresh.setLines(new ArrayList<>());
                return fresh;
              });

      CafeKotLine line = new CafeKotLine();
      line.setLineId(delta.getString("sellableRef"));
      line.setName(delta.getString("name"));
      // A cancellation tells the station how many to stop, which is never a negative count.
      line.setQuantity(Math.abs(quantity));
      line.setNote(delta.getString("note"));
      kot.getLines().add(line);
    }
    return List.copyOf(byKey.values());
  }

  /** Finishes the punch in place. Scoped by shop, and matched by punchId so {@code $} is that punch. */
  private void markComplete(String shopId, String purchaseId, String punchId, List<String> kotIds) {
    Query query =
        Query.query(
            Criteria.where("_id")
                .is(purchaseId)
                .and("shopId")
                .is(shopId)
                .and(PUNCHES + ".punchId")
                .is(punchId));
    Update update = new Update().set(PUNCHES + ".$.status", COMPLETE).set(PUNCHES + ".$.kotIds", kotIds);
    mongoTemplate.updateFirst(query, update, PURCHASES);
  }

  private Document requirePurchase(String shopId, String purchaseId) {
    Document purchase =
        mongoTemplate.findOne(
            Query.query(Criteria.where("_id").is(purchaseId).and("shopId").is(shopId)),
            Document.class,
            PURCHASES);
    if (purchase == null) {
      throw new ResourceNotFoundException("Purchase", "id", purchaseId);
    }
    return purchase;
  }

  private static Document findPunch(Document purchase, String idempotencyKey) {
    for (Document punch : punches(purchase)) {
      if (idempotencyKey.equals(punch.getString("idempotencyKey"))) {
        return punch;
      }
    }
    return null;
  }

  /** The punch's position on the cart, one-based: the round the kitchen sees on the paper. */
  private static int roundNo(Document purchase, String punchId) {
    List<Document> punches = punches(purchase);
    for (int i = 0; i < punches.size(); i++) {
      if (punchId != null && punchId.equals(punches.get(i).getString("punchId"))) {
        return i + 1;
      }
    }
    // The caller always passes the punchId of a punch it just read off this very purchase
    // document, so it must be found above. Silently falling back to punches.size() would hand out
    // a duplicate round number instead of surfacing the inconsistency.
    throw new IllegalStateException(
        "Punch " + punchId + " is not among the punches on purchase " + purchase.get("_id"));
  }

  @SuppressWarnings("unchecked")
  private static List<Document> punches(Document purchase) {
    List<Document> punches = (List<Document>) purchase.get(PUNCHES);
    return punches == null ? List.of() : punches;
  }

  @SuppressWarnings("unchecked")
  private static List<Document> deltas(Document punch) {
    List<Document> deltas = (List<Document>) punch.get("deltas");
    return deltas == null ? List.of() : deltas;
  }

  @SuppressWarnings("unchecked")
  private static List<String> stringList(Document punch, String field) {
    List<String> values = (List<String>) punch.get(field);
    return values == null ? List.of() : values;
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
