package com.inventory.plugins.cafe;

import com.inventory.common.exception.ResourceNotFoundException;
import com.inventory.common.exception.ValidationException;
import com.inventory.plugins.cafe.domain.CafeKot;
import com.inventory.plugins.cafe.domain.CafeKotKind;
import com.inventory.plugins.cafe.domain.CafeKotLine;
import com.inventory.plugins.cafe.domain.CafeKotRepository;
import com.inventory.plugins.cafe.domain.CafeKotStatus;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
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
 * Tells the kitchen to stop making something.
 *
 * <p>A bill line arrives already sent: {@code kotSentQuantity} equals its quantity, because the
 * Sell screen never issues to the kitchen — only the KOT tab screen does, via {@link
 * CafeFlushService}. The Sell screen only ever withdraws. Reducing a line below {@code
 * kotSentQuantity} owes the kitchen a cancellation for the difference; removing it owes one for
 * the whole remainder. Raising a quantity owes nothing — the extra is unsent and goes through a
 * new tab round. This is the only remaining delta in the feature, and it is negative-only, so the
 * ticket this service writes always carries an absolute, positive quantity.
 *
 * <p><b>The shape mirrors {@link CafeFlushService} deliberately, not by coincidence.</b> There is
 * no {@code MongoTransactionManager} in this codebase and a cancellation touches two documents
 * (the bill and the ticket collection), so the same discipline applies: a small idempotent record
 * — {@code Purchase.cafeKotCancels[]} — is written PENDING before the ticket, and only the ticket
 * actually landing lets it become COMPLETE. Unlike the flush, both the claim and the "append" live
 * on the very same document (the bill), so the claim needs no self-referential aggregation
 * pipeline: a plain {@code $push} guarded by the same {@code $ne} idempotency clause the flush
 * uses is enough. The produced ticket's {@code _id} is {@code {cancelId}:{department}:CANCEL},
 * exactly the flush's {@code {flushId}:{department}:ISSUE} shape, and — because one cancel call
 * targets exactly one line and therefore exactly one station — the {@code flushId} field on {@link
 * CafeKot} is reused rather than duplicated with a second field of the same purpose: it holds
 * whichever operation produced the ticket, a flush's id or a cancel's.
 *
 * <p>The bill is a {@code Purchase} in {@code core/product}, which {@code plugins/cafe} does not
 * depend on and must not. As {@link CafeFlushService} does, the document is reached as a raw
 * {@link Document} through {@link MongoTemplate}.
 *
 * <p><b>The stamp.</b> A ticket this service writes always has {@code kind == CANCEL}. Rendering
 * lives in {@code core/product}'s {@code CafeKotService}, which stamps {@code KotStamp.CANCELLED}
 * whenever a ticket's kind is {@code CANCEL} — so a cancellation slip can never render unstamped
 * as long as {@code kind} is set here, which it always is.
 */
@Service
@Slf4j
public class CafeKotCancelService {

  private static final String PURCHASES = "purchases";
  private static final String CANCELS = "cafeKotCancels";

  private static final String STATUS_PENDING = "PENDING";
  private static final String STATUS_COMPLETE = "COMPLETE";

  private final MongoTemplate mongoTemplate;
  private final CafeKotRepository cafeKotRepository;
  private final CafeSequenceService cafeSequenceService;

  public CafeKotCancelService(
      MongoTemplate mongoTemplate,
      CafeKotRepository cafeKotRepository,
      CafeSequenceService cafeSequenceService) {
    this.mongoTemplate = mongoTemplate;
    this.cafeKotRepository = cafeKotRepository;
    this.cafeSequenceService = cafeSequenceService;
  }

  /**
   * Cancels the withdrawn portion of one bill line, or finishes a cancel an earlier attempt left
   * half-done.
   *
   * @param fromQty the line's quantity before this reduction, as the caller understood it.
   * @param toQty the line's new quantity; {@code 0} for a removed line.
   * @return the tickets this idempotency key stands for. Empty when nothing was owed — the line
   *     was never sent, or the quantity did not go down.
   */
  public List<CafeKot> cancel(
      String shopId,
      String userId,
      String purchaseId,
      String lineRef,
      int fromQty,
      int toQty,
      String idempotencyKey) {

    if (!StringUtils.hasText(shopId) || !StringUtils.hasText(userId)) {
      throw new ValidationException("shopId and userId are required to cancel a kitchen line");
    }
    if (!StringUtils.hasText(purchaseId) || !StringUtils.hasText(lineRef)) {
      throw new ValidationException("purchaseId and lineRef are required to cancel a kitchen line");
    }
    if (!StringUtils.hasText(idempotencyKey)) {
      throw new ValidationException("Idempotency-Key is required when cancelling to the kitchen");
    }

    Document bill = requirePurchase(shopId, purchaseId);

    // Replay of an exact key is answered from the record, never recomputed. The claim below
    // mutates the line's kotSentQuantity on success (see the claim's own comment), so a second
    // look at the live line under the SAME key could compute a different — even zero — delta and
    // wrongly conclude nothing is owed. Checking the key first keeps replay exact regardless of
    // what has happened to the line since.
    if (findCancelRecord(bill, idempotencyKey).isPresent()) {
      return resume(shopId, purchaseId, idempotencyKey);
    }

    // The flush's invariant, and for the same reason: a bill that still owes the kitchen an
    // earlier cancel has that one finished first. Without this, a PENDING record under a
    // DIFFERENT key is invisible -- the check above recognises only this exact key -- and nothing
    // in the system would ever look at it again: there is no sweep and no redrive. Its
    // kotSentQuantity has already been decremented, so the withdrawal it stands for is taken off
    // the bill and never told to the kitchen. That is the stranded sequence: reduce 5 -> 3 claims
    // and decrements but dies before its ticket; the cashier reduces to 2 instead of retrying;
    // 3 are withdrawn from the bill and the kitchen hears about 1.
    bill = finishStrandedCancels(shopId, purchaseId, bill);

    Document item = requireItem(bill, lineRef);

    int kotSentQuantity = intField(item, "kotSentQuantity");
    // The delta, framed the way the spec frames it: negative-only. toQty is capped against
    // kotSentQuantity via fromQty so a line the kitchen never fully saw is not over-cancelled.
    // Zero or positive means nothing is owed — a raise, or a reduction that never dips below what
    // was sent — and is handled by returning before a ticket is ever built.
    int delta = toQty - Math.min(fromQty, kotSentQuantity);

    if (delta >= 0) {
      log.debug(
          "Cafe cancel on bill {} line {} in shop {} owes the kitchen nothing (sent {}, {} -> {})",
          purchaseId,
          lineRef,
          shopId,
          kotSentQuantity,
          fromQty,
          toQty);
      return List.of();
    }

    // The ticket must never carry a negative quantity: the delta above is negative by
    // construction whenever anything is owed, and this is the one place that turns it positive.
    int quantity = Math.abs(delta);

    String cancelId = UUID.randomUUID().toString();
    Document record =
        new Document("cancelId", cancelId)
            .append("idempotencyKey", idempotencyKey)
            .append("lineRef", lineRef)
            .append("quantity", quantity)
            .append("department", item.getString("department"))
            .append("note", item.getString("note"))
            .append("name", item.getString("name"))
            .append("status", STATUS_PENDING)
            .append("createdBy", userId)
            .append("createdAt", Instant.now());

    // The idempotency lives in this query clause, not an index — a unique multikey index would
    // not stop a second push into the same document's array, same reasoning as the flush's claim.
    // A bill with no cafeKotCancels array yet still matches: $ne against an absent field is true.
    //
    // That alone only guards exact-key replay. Two concurrent callers with DIFFERENT keys — a
    // retried request that regenerated its key, or two staff edits racing — would both read the
    // same kotSentQuantity above and both match this query, both pushing a ticket and together
    // over-cancelling. So the claim also requires the line's kotSentQuantity to still be what was
    // just read: elemMatch on lineRef + kotSentQuantity. A concurrent winner's claim (below)
    // changes that value, so a second, stale writer's claim query no longer matches its item and
    // fails — landing in resume(), which throws for an unrecognised key rather than silently
    // treating "someone else's write" as "my own already-claimed write".
    Query claimQuery =
        Query.query(
            Criteria.where("_id")
                .is(purchaseId)
                .and("shopId")
                .is(shopId)
                .and(CANCELS + ".idempotencyKey")
                .ne(idempotencyKey)
                // The flush's PENDING clause, on the same document rather than on a tab: a bill
                // that still owes the kitchen a cancel is never claimed out from under it. The
                // sweep above finishes any such record before we get here; this clause is what
                // makes that check hold across the gap between its read and this write, exactly
                // as CafeTabFlusher's pendingFlush.status clause does for a flush. $ne on an
                // array means "no element has this value", and it matches a bill with no
                // cafeKotCancels at all, which is the ordinary case.
                .and(CANCELS + ".status")
                .ne(STATUS_PENDING)
                .and("items")
                .elemMatch(
                    Criteria.where("lineRef")
                        .is(lineRef)
                        .and("kotSentQuantity")
                        .is(kotSentQuantity)));
    Update update =
        new Update()
            .push(CANCELS, record)
            .set("items.$.kotSentQuantity", kotSentQuantity - quantity)
            .set("updatedAt", Instant.now());

    long matched = mongoTemplate.updateFirst(claimQuery, update, PURCHASES).getMatchedCount();
    if (matched == 0) {
      // Either this key already claimed (the normal recovery case), the line's kotSentQuantity
      // moved under us (a concurrent claim won; the caller must re-read and retry), or the bill
      // vanished. requirePurchase, inside resume, throws on the last; an unrecognised key throws
      // on the middle one too — there is nothing to replay, only a stale read to redo.
      return resume(shopId, purchaseId, idempotencyKey);
    }

    log.info(
        "Claimed cafe cancel {} on bill {} line {} in shop {}: {} to cancel",
        cancelId,
        purchaseId,
        lineRef,
        shopId,
        quantity);
    return finish(shopId, bill, record);
  }

  /**
   * Finishes every cancel this bill still owes the kitchen before a new one may claim — the
   * flush's "finish the older one first" step, which the cancel path used not to have.
   *
   * <p>Each one is finished by the same {@link #finish} a resume uses, so it is idempotent: a
   * ticket a previous attempt already wrote is found and re-used rather than renumbered, and the
   * record is marked COMPLETE only by the work having been done. The bill is re-read afterwards
   * so the claim that follows is computed against the document as it now stands, not as it was
   * before the sweep.
   *
   * @return the bill to carry on with — re-read when anything was finished, the same one when
   *     there was nothing owed.
   */
  private Document finishStrandedCancels(String shopId, String purchaseId, Document bill) {
    List<Document> stranded =
        cancelsOf(bill).stream()
            .filter(record -> STATUS_PENDING.equals(record.getString("status")))
            .toList();
    if (stranded.isEmpty()) {
      return bill;
    }
    for (Document record : stranded) {
      log.warn(
          "Bill {} in shop {} still owes cancel {}; finishing it before claiming another",
          purchaseId,
          shopId,
          record.getString("cancelId"));
      finish(shopId, bill, record);
    }
    return requirePurchase(shopId, purchaseId);
  }

  /** Disambiguates an unclaimed write by reading the bill, rather than by guessing. */
  private List<CafeKot> resume(String shopId, String purchaseId, String idempotencyKey) {
    Document bill = requirePurchase(shopId, purchaseId);
    Document record =
        findCancelRecord(bill, idempotencyKey)
            .orElseThrow(
                () ->
                    new ValidationException(
                        "Cancel " + idempotencyKey + " could not be claimed; retry the request"));

    if (STATUS_COMPLETE.equals(record.getString("status"))) {
      List<CafeKot> done =
          cafeKotRepository.findByShopIdAndFlushId(shopId, record.getString("cancelId"));
      log.info(
          "Replayed complete cafe cancel {} on shop {}: {} ticket(s)",
          record.getString("cancelId"),
          shopId,
          done.size());
      return done;
    }

    log.warn(
        "Resuming cafe cancel {} on bill {} in shop {} after an interrupted attempt",
        record.getString("cancelId"),
        purchaseId,
        shopId);
    return finish(shopId, bill, record);
  }

  /**
   * Writes the ticket this cancel is missing, then marks the record COMPLETE. A no-op ticket
   * write when a previous attempt already wrote it — the repository lookup runs before any save
   * or sequence allocation, exactly as the flush's does, so a recovery never renumbers or
   * duplicates a ticket a cook may already be holding.
   */
  private List<CafeKot> finish(String shopId, Document bill, Document record) {
    String cancelId = record.getString("cancelId");
    String purchaseId = String.valueOf(bill.get("_id"));

    List<CafeKot> existing = cafeKotRepository.findByShopIdAndFlushId(shopId, cancelId);
    if (!existing.isEmpty()) {
      markComplete(shopId, purchaseId, cancelId);
      return existing;
    }

    // The station frozen onto the line at compose time, carried onto the record unchanged — never
    // re-resolved here. A menu edit after the line was added must not reroute this ticket.
    String department = record.getString("department");

    CafeKotLine line = new CafeKotLine();
    line.setLineId(record.getString("lineRef"));
    line.setName(record.getString("name"));
    line.setQuantity(record.getInteger("quantity"));
    line.setNote(record.getString("note"));

    CafeKot kot = new CafeKot();
    kot.setId(cancelId + ":" + department + ":" + CafeKotKind.CANCEL.name());
    kot.setShopId(shopId);
    kot.setPurchaseId(purchaseId);
    kot.setDepartment(department);
    // The stamp: CANCEL is what CafeKotService.isCancelled reads to render KotStamp.CANCELLED.
    kot.setKind(CafeKotKind.CANCEL);
    kot.setStatus(CafeKotStatus.ISSUED);
    kot.setTableLabel(bill.getString("tableLabel"));
    kot.setTokenNo(bill.getString("tokenNo"));
    // Reused, not duplicated: see the class javadoc.
    kot.setFlushId(cancelId);
    kot.setBusinessDate(LocalDate.now().toString());
    kot.setCreatedAt(Instant.now());
    kot.setCreatedBy(record.getString("createdBy"));
    kot.setLines(new ArrayList<>(List.of(line)));
    kot.setKotNo(cafeSequenceService.allocate(shopId, LocalDate.now(), CafeSequenceSeries.KOT));

    CafeKot saved = cafeKotRepository.save(kot);
    markComplete(shopId, purchaseId, cancelId);
    return List.of(saved);
  }

  /** The last step, and the only thing allowed to clear a PENDING record. */
  private void markComplete(String shopId, String purchaseId, String cancelId) {
    Query query =
        Query.query(
            Criteria.where("_id")
                .is(purchaseId)
                .and("shopId")
                .is(shopId)
                .and(CANCELS + ".cancelId")
                .is(cancelId));
    Update update = new Update().set(CANCELS + ".$.status", STATUS_COMPLETE);
    mongoTemplate.updateFirst(query, update, PURCHASES);
  }

  // ----------------------------------------------------------------- helpers

  private static Optional<Document> findCancelRecord(Document bill, String idempotencyKey) {
    return cancelsOf(bill).stream()
        .filter(r -> idempotencyKey.equals(r.getString("idempotencyKey")))
        .findFirst();
  }

  @SuppressWarnings("unchecked")
  private static List<Document> cancelsOf(Document bill) {
    Object raw = bill.get(CANCELS);
    return raw == null ? List.of() : (List<Document>) raw;
  }

  private static Document requireItem(Document bill, String lineRef) {
    return itemsOf(bill).stream()
        .filter(i -> lineRef.equals(i.getString("lineRef")))
        .findFirst()
        .orElseThrow(() -> new ResourceNotFoundException("PurchaseItem", "lineRef", lineRef));
  }

  @SuppressWarnings("unchecked")
  private static List<Document> itemsOf(Document bill) {
    Object raw = bill.get("items");
    return raw == null ? List.of() : (List<Document>) raw;
  }

  private static int intField(Document doc, String field) {
    Number n = (Number) doc.get(field);
    return n == null ? 0 : n.intValue();
  }

  private Document requirePurchase(String shopId, String purchaseId) {
    Document bill =
        mongoTemplate.findOne(
            Query.query(Criteria.where("_id").is(purchaseId).and("shopId").is(shopId)),
            Document.class,
            PURCHASES);
    if (bill == null) {
      throw new ResourceNotFoundException("Purchase", "id", purchaseId);
    }
    return bill;
  }
}
