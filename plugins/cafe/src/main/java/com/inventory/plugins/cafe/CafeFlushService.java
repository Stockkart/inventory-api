package com.inventory.plugins.cafe;

import com.inventory.common.exception.ResourceNotFoundException;
import com.inventory.common.exception.ValidationException;
import com.inventory.pluginengine.cart.CartLineAmountCalculator;
import com.inventory.pluginengine.cart.CartTotalsPort;
import com.inventory.pluginengine.integration.ShopMenuLookup;
import com.inventory.pluginengine.menu.MenuDepartments;
import com.inventory.pluginengine.menu.MenuItem;
import com.inventory.pluginengine.ref.SellableRef;
import com.inventory.plugins.cafe.domain.CafeFlushStatus;
import com.inventory.plugins.cafe.domain.CafeKot;
import com.inventory.plugins.cafe.domain.CafeKotKind;
import com.inventory.plugins.cafe.domain.CafeKotLine;
import com.inventory.plugins.cafe.domain.CafeKotRepository;
import com.inventory.plugins.cafe.domain.CafeKotStatus;
import com.inventory.plugins.cafe.domain.CafePendingFlush;
import com.inventory.plugins.cafe.domain.CafeRecentFlush;
import com.inventory.plugins.cafe.domain.CafeTab;
import com.inventory.plugins.cafe.domain.CafeTabLine;
import com.inventory.plugins.cafe.domain.CafeTabRepository;
import com.inventory.plugins.cafe.domain.CafeTabStatus;
import java.math.BigDecimal;
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
 * Sends a tab's unsent lines to the kitchen and onto a bill.
 *
 * <p>Four steps, in this order, and each one individually recoverable:
 *
 * <ol>
 *   <li><b>Claim</b> — {@link CafeTabFlusher}, one {@code findAndModify}: the tab's lines move into
 *       a {@code pendingFlush} record and the tab is left empty.
 *   <li><b>Append</b> the claimed lines to the target bill, idempotent on {@code flushId}: the bill
 *       records which flushes it has absorbed and the query refuses one it already holds. The
 *       lines are priced as the add-to-cart path prices them, and the bill's totals are then
 *       recomputed from what it now holds — a raw {@code $push} moves no money on its own, and
 *       checkout settles against the bill's <i>stored</i> {@code grandTotal}.
 *   <li><b>Create tickets</b>, {@code _id = {flushId}:{department}:ISSUE}, after filtering the
 *       desired set against what the repository already holds for this flush.
 *   <li><b>Mark</b> the flush COMPLETE.
 * </ol>
 *
 * <p>There is no {@code MongoTransactionManager} in this codebase and this operation touches three
 * documents, so none of the three gaps between those steps can be closed. Each is instead made
 * harmless: retrying with the same idempotency key resumes from wherever the previous attempt
 * stopped, and every step is a no-op when it has already run.
 *
 * <p><b>The invariant:</b> a tab whose {@code pendingFlush} is PENDING owes lines to a kitchen that
 * has not been told. Nothing clears that record except the work completing — which is why a flush
 * arriving at a tab that still owes an <i>earlier</i> flush finishes that one first rather than
 * overwriting it.
 *
 * <p>The bill is a {@code Purchase} in {@code core/product}, which {@code plugins/cafe} does not
 * depend on and must not. As the retired {@code CafeCartPuncher} did, the document is reached as a
 * raw {@link Document} through {@link MongoTemplate} and the handful of enum values it needs are
 * string literals here. The backend creates tickets; printing them is somebody else's job.
 */
@Service
@Slf4j
public class CafeFlushService {

  private static final String PURCHASES = "purchases";
  private static final String TABS = CafeTabFlusher.COLLECTION;

  /** The bill's record of which flushes it has already absorbed. */
  private static final String FLUSH_IDS = "cafeFlushIds";

  /** {@code PurchaseStatus.CREATED} — an open quotation. A literal: cafe does not see core. */
  private static final String STATUS_CREATED = "CREATED";

  /** {@code DocumentType.SALE}. */
  private static final String DOCUMENT_TYPE_SALE = "SALE";

  /** {@code BillingMode.REGULAR}. */
  private static final String BILLING_MODE_REGULAR = "REGULAR";

  /** {@code PurchaseItem.sellMode} for a menu line. */
  private static final String SELL_MODE_MENU = "menu";

  /** A menu item is sold by the piece, as {@code CafeMenuCartLineContributor} also has it. */
  private static final String SALE_UNIT_PCS = "PCS";

  private final MongoTemplate mongoTemplate;
  private final CafeTabFlusher flusher;
  private final CafeTabRepository cafeTabRepository;
  private final CafeKotRepository cafeKotRepository;
  private final CafeSequenceService cafeSequenceService;
  private final CafeTokenService cafeTokenService;
  private final ShopMenuLookup shopMenuLookup;
  private final CartTotalsPort cartTotalsPort;

  public CafeFlushService(
      MongoTemplate mongoTemplate,
      CafeTabFlusher flusher,
      CafeTabRepository cafeTabRepository,
      CafeKotRepository cafeKotRepository,
      CafeSequenceService cafeSequenceService,
      CafeTokenService cafeTokenService,
      ShopMenuLookup shopMenuLookup,
      CartTotalsPort cartTotalsPort) {
    this.mongoTemplate = mongoTemplate;
    this.flusher = flusher;
    this.cafeTabRepository = cafeTabRepository;
    this.cafeKotRepository = cafeKotRepository;
    this.cafeSequenceService = cafeSequenceService;
    this.cafeTokenService = cafeTokenService;
    this.shopMenuLookup = shopMenuLookup;
    this.cartTotalsPort = cartTotalsPort;
  }

  /**
   * Sends this tab's unsent lines, or finishes a flush an earlier attempt left half-done.
   *
   * @param targetPurchaseId the open bill to append to; {@code null} asks for a new one.
   * @return the tickets this idempotency key stands for — the ones created here, and on a replay
   *     the ones the key created the first time.
   */
  public List<CafeKot> flush(
      String shopId, String userId, String tabId, String targetPurchaseId, String idempotencyKey) {

    if (!StringUtils.hasText(shopId) || !StringUtils.hasText(userId)) {
      throw new ValidationException("shopId and userId are required to flush a tab");
    }
    if (!StringUtils.hasText(tabId)) {
      throw new ValidationException("tabId is required to flush a tab");
    }
    if (!StringUtils.hasText(idempotencyKey)) {
      throw new ValidationException("Idempotency-Key is required when flushing a tab");
    }

    CafeTab tab = requireTab(shopId, userId, tabId);
    if (tab.getStatus() != CafeTabStatus.OPEN) {
      throw new ValidationException("Tab is not open (status: " + tab.getStatus() + ")");
    }

    CafePendingFlush recorded = tab.getPendingFlush();
    boolean isReplayOfRecorded =
        recorded != null && idempotencyKey.equals(recorded.getIdempotencyKey());

    // A key from a flush or two ago, replayed: the client parks its key in sessionStorage, so it
    // outlives the component that issued it and can arrive after two more flushes have moved
    // pendingFlush on. Answering it with the tickets it created is the contract; claiming under
    // it would send a round the caller never composed and hand back tickets for something else.
    if (!isReplayOfRecorded) {
      Optional<CafeRecentFlush> stale = recentFlush(tab, idempotencyKey);
      if (stale.isPresent()) {
        String staleFlushId = stale.get().getFlushId();
        List<CafeKot> done = cafeKotRepository.findByShopIdAndFlushId(shopId, staleFlushId);
        log.warn(
            "Stale cafe flush key {} on tab {} in shop {}: returning flush {}'s {} ticket(s), "
                + "claiming nothing",
            idempotencyKey,
            tabId,
            shopId,
            staleFlushId,
            done.size());
        return done;
      }
    }

    // The invariant, enforced before anything can overwrite the record: a tab that still owes an
    // earlier flush is finished first. The claim's $ne would happily replace a PENDING record
    // belonging to a different key, and that record is the only evidence those lines exist.
    if (!isReplayOfRecorded && recorded != null && recorded.getStatus() == CafeFlushStatus.PENDING) {
      log.warn(
          "Cafe tab {} still owes flush {}; finishing it before claiming for key {}",
          tabId,
          recorded.getFlushId(),
          idempotencyKey);
      finish(shopId, userId, tab, recorded);
    }

    // Null as well as empty: the claim pipeline uses $ifNull for this same field because a tab
    // document need not carry a lines array at all, and reading it here must be as forgiving.
    if (!isReplayOfRecorded && (tab.getLines() == null || tab.getLines().isEmpty())) {
      throw new ValidationException("Tab has nothing unsent to send to the kitchen");
    }

    String flushId = UUID.randomUUID().toString();
    // Decided BEFORE the claim and recorded by it, so a retry lands on the same bill rather than
    // creating a second one. A new bill's id is derived from the flush that asked for it, which
    // makes creating it idempotent without a second record of the fact.
    String target =
        StringUtils.hasText(targetPurchaseId) ? targetPurchaseId : newBillId(flushId);

    Optional<CafeTab> claimed =
        flusher.claim(shopId, userId, tabId, flushId, idempotencyKey, target);

    if (claimed.isEmpty()) {
      // A findAndModify that matched nothing returns empty for two different reasons — no such tab
      // for this shop and cashier, and this key has already claimed. The empty result cannot tell
      // them apart, so the tab is re-read and the answer taken off the document.
      return resume(shopId, userId, tabId, idempotencyKey);
    }

    CafeTab preImage = claimed.get();
    CafePendingFlush pending = new CafePendingFlush();
    pending.setFlushId(flushId);
    pending.setIdempotencyKey(idempotencyKey);
    // The pre-image's lines: exactly what the claim removed from the tab.
    pending.setLines(preImage.getLines());
    pending.setTargetPurchaseId(target);
    pending.setStatus(CafeFlushStatus.PENDING);

    return finish(shopId, userId, preImage, pending);
  }

  /** Disambiguates an empty claim by reading the tab, rather than by guessing from the emptiness. */
  private List<CafeKot> resume(
      String shopId, String userId, String tabId, String idempotencyKey) {

    // Throws if the tab is genuinely absent — the first of the two reasons.
    CafeTab tab = requireTab(shopId, userId, tabId);
    CafePendingFlush pending = tab.getPendingFlush();

    if (pending == null || !idempotencyKey.equals(pending.getIdempotencyKey())) {
      Optional<CafeRecentFlush> stale = recentFlush(tab, idempotencyKey);
      if (stale.isPresent()) {
        // The key won a claim once, and later flushes have moved pendingFlush past it.
        return cafeKotRepository.findByShopIdAndFlushId(shopId, stale.get().getFlushId());
      }
      // The tab exists, the claim did not land, and no flush on it carries this key: nothing was
      // claimed, so nothing is owed and the caller may safely retry. This is also where a flush
      // refused by the PENDING clause lands — another flush is in flight and owes the kitchen
      // lines, and these were never taken from the tab, so a retry is exactly right.
      log.warn(
          "Cafe flush of tab {} in shop {} with key {} claimed nothing and left no record",
          tabId,
          shopId,
          idempotencyKey);
      throw new ValidationException(
          "Flush " + idempotencyKey + " could not be claimed; retry the request");
    }

    if (pending.getStatus() == CafeFlushStatus.COMPLETE) {
      List<CafeKot> done = cafeKotRepository.findByShopIdAndFlushId(shopId, pending.getFlushId());
      log.info(
          "Replayed complete cafe flush {} on shop {}: {} ticket(s)",
          pending.getFlushId(),
          shopId,
          done.size());
      return done;
    }

    log.warn(
        "Resuming cafe flush {} on tab {} in shop {} after an interrupted attempt",
        pending.getFlushId(),
        tabId,
        shopId);
    return finish(shopId, userId, tab, pending);
  }

  /** Steps 2, 3 and 4. Every one of them is a no-op when a previous attempt already ran it. */
  private List<CafeKot> finish(
      String shopId, String userId, CafeTab tab, CafePendingFlush pending) {

    String flushId = pending.getFlushId();
    String target = pending.getTargetPurchaseId();
    List<CafeTabLine> lines = pending.getLines() == null ? List.of() : pending.getLines();

    // Step 2 — append, idempotent on flushId, and then the money the append moved.
    if (target.equals(newBillId(flushId))) {
      ensureNewBill(shopId, userId, target);
    }
    target = retargetIfSettled(shopId, userId, tab, pending, target);
    append(shopId, target, flushId, lines);
    // The append is a raw $push and touches no total. Nothing downstream repairs that: checkout
    // completion reads the STORED grandTotal, so without this the bill settles for what it held
    // before the round was added — zero, on a bill the round opened. Idempotent by construction:
    // it recomputes from whatever lines the bill now holds, so a retry that appended nothing
    // recomputes the same numbers.
    cartTotalsPort.recalculateTotals(shopId, target);
    Document bill = requirePurchase(shopId, target);

    // Step 3 — tickets.
    List<CafeKot> tickets = createTickets(shopId, userId, bill, flushId, lines);

    // Step 4 — complete. Only now, and only ever by the work having been done.
    markComplete(shopId, tab.getId(), flushId);
    return tickets;
  }

  // ------------------------------------------------------------------ step 2

  /**
   * Moves this flush to a bill of its own when the one it was aimed at has since been settled.
   *
   * <p>A bill that is no longer CREATED has an invoice number and money taken against it, and its
   * totals are frozen at what was settled — {@link CartTotalsPort} will not recompute them, and
   * rightly. Appending to it anyway lands priced lines on a closed invoice that contribute to no
   * total: free food, and a settled document quietly changed after the fact. That is what the
   * table having paid and closed while a retry was still in flight looks like.
   *
   * <p>Refusing is not available: the lines are already claimed, so a throw would strand the tab
   * owing a kitchen that was never told. So the round goes onto a new bill instead, the same one
   * a flush with no target would have opened — derived from the flush id, so every retry derives
   * the same bill rather than opening another. The tab's record is moved with it, and the party
   * has a second bill for the round they ordered after settling the first, which is what happened.
   *
   * @return the bill to append to: the original one, or the new one this flush now owns.
   */
  private String retargetIfSettled(
      String shopId, String userId, CafeTab tab, CafePendingFlush pending, String target) {

    String flushId = pending.getFlushId();
    Document bill =
        mongoTemplate.findOne(
            Query.query(Criteria.where("_id").is(target).and("shopId").is(shopId)),
            Document.class,
            PURCHASES);
    if (bill == null) {
      // Not this method's business: the append will match nothing and requirePurchase throws.
      return target;
    }
    String status = bill.getString("status");
    if (status == null || STATUS_CREATED.equals(status)) {
      return target;
    }
    List<?> absorbed = (List<?>) bill.get(FLUSH_IDS);
    if (absorbed != null && absorbed.contains(flushId)) {
      // The lines are already on it and were priced into the total it settled for. Nothing to
      // move; the append is a no-op and the rest of the flush finishes against this bill.
      return target;
    }

    String fresh = newBillId(flushId);
    if (fresh.equals(target)) {
      // The bill this flush opened for itself was settled before the flush could append to it.
      // There is no second bill to derive, and inventing one from a fresh id would make a retry
      // open another. Nothing here is safe, and saying so beats writing onto a paid invoice.
      throw new ValidationException(
          "Bill " + target + " was settled while flush " + flushId + " was in flight");
    }

    log.warn(
        "Bill {} in shop {} is {}; moving cafe flush {} onto its own bill {}",
        target,
        shopId,
        status,
        flushId,
        fresh);
    ensureNewBill(shopId, userId, fresh);
    // Recorded before the append, so a retry reads the new target off the tab rather than
    // rediscovering it. The id is derived from the flush either way, so the two agree.
    mongoTemplate.updateFirst(
        Query.query(
            Criteria.where("_id")
                .is(tab.getId())
                .and("shopId")
                .is(shopId)
                .and("pendingFlush.flushId")
                .is(flushId)),
        new Update().set("pendingFlush.targetPurchaseId", fresh),
        TABS);
    pending.setTargetPurchaseId(fresh);
    return fresh;
  }

  /**
   * Creates the bill this flush asked for, if it is not already there.
   *
   * <p>Separate from the append so that neither write needs an upsert guarded by a {@code $ne} — an
   * upsert whose query excludes an already-absorbed flush would try to insert a duplicate {@code
   * _id} on the very replay it is meant to ignore. A crash between the two leaves an empty open
   * bill, which the retry then appends to.
   */
  private void ensureNewBill(String shopId, String userId, String purchaseId) {
    Query query = Query.query(Criteria.where("_id").is(purchaseId).and("shopId").is(shopId));
    if (mongoTemplate.findOne(query, Document.class, PURCHASES) != null) {
      return;
    }

    Instant now = Instant.now();
    Update update =
        new Update()
            .setOnInsert("shopId", shopId)
            .setOnInsert("userId", userId)
            .setOnInsert("status", STATUS_CREATED)
            .setOnInsert("documentType", DOCUMENT_TYPE_SALE)
            .setOnInsert("billingMode", BILLING_MODE_REGULAR)
            .setOnInsert("valid", true)
            .setOnInsert("items", List.of())
            .setOnInsert(FLUSH_IDS, List.of())
            // Every money field ZERO, exactly as QuotationService.createQuotation opens a bill.
            // Null would read as a blank rate column in Sell and, in the window between this
            // insert and the append's recompute, would make an open bill with no total at all.
            .setOnInsert("subTotal", BigDecimal.ZERO)
            .setOnInsert("taxTotal", BigDecimal.ZERO)
            .setOnInsert("sgstAmount", BigDecimal.ZERO)
            .setOnInsert("cgstAmount", BigDecimal.ZERO)
            .setOnInsert("discountTotal", BigDecimal.ZERO)
            .setOnInsert("saleAdditionalDiscountTotal", BigDecimal.ZERO)
            .setOnInsert("grandTotal", BigDecimal.ZERO)
            // The bill's own token, from the scope the bill has always allocated under — never the
            // tab's, which numbers a different thing.
            .setOnInsert("tokenNo", cafeTokenService.allocateToken(shopId))
            .setOnInsert("createdAt", now)
            .setOnInsert("updatedAt", now);
    mongoTemplate.upsert(query, update, PURCHASES);
    log.info("Opened bill {} for shop {} to receive a cafe flush", purchaseId, shopId);
  }

  /**
   * Appends the claimed lines to the bill in one write, refusing a flush the bill already holds.
   *
   * <p>The {@code $ne} on {@link #FLUSH_IDS} and the {@code $push} of the flush id are in the same
   * update as the lines, so "the bill has these lines" and "the bill has absorbed this flush" can
   * never disagree — the pair is what makes a retry safe.
   */
  private void append(String shopId, String purchaseId, String flushId, List<CafeTabLine> lines) {
    Query query =
        Query.query(
            Criteria.where("_id")
                .is(purchaseId)
                .and("shopId")
                .is(shopId)
                // The idempotency, in the query clause for the same reason the claim's is.
                .and(FLUSH_IDS)
                .ne(flushId));

    List<Document> billLines = lines.stream().map(line -> billLine(shopId, line)).toList();
    Update update =
        new Update()
            .push("items", new Document("$each", billLines))
            .push(FLUSH_IDS, flushId)
            .set("updatedAt", Instant.now());

    long matched = mongoTemplate.updateFirst(query, update, PURCHASES).getMatchedCount();
    if (matched == 0) {
      // Either the bill already holds this flush — the normal recovery case — or it is gone.
      // requirePurchase, next, tells those apart by throwing on the second.
      log.debug("Cafe flush {} was already absorbed by bill {}", flushId, purchaseId);
      return;
    }
    log.info("Appended {} line(s) from cafe flush {} to bill {}", billLines.size(), flushId, purchaseId);
  }

  /**
   * One bill line per claimed tab line, priced.
   *
   * <p>{@code kotSentQuantity} equals the quantity because these lines arrive already sent: this is
   * the flush that hands them to the kitchen, so there is never a moment when the bill holds them
   * unsent. {@code department} and {@code note} ride along because a later reduction of this line
   * owes the kitchen a cancellation, and it has to know which station to tell.
   *
   * <p>{@code lineRef} is carried over from the tab line, and is the line's identity where {@code
   * sellableRef} is only the identity of what is being sold. Two lines of "menu:tea" — one no
   * sugar, one extra hot — are composed separately, are never merged by the tab or by the {@code
   * $push} above, and whoever later reduces the second of them must be able to say which one it
   * was. Addressing by {@code sellableRef} would cancel the first line's note and quantity.
   *
   * <p><b>The money, and where it comes from.</b> Nothing downstream computes it: checkout
   * completion reads the stored {@code grandTotal}, and a line written without a price is a line
   * the shop gives away. It comes off the <b>tab line</b>, frozen there when the line was composed
   * and the customer was quoted — see {@link CafeTabLine}. The menu is not consulted again, so an
   * item deleted between composing and sending cannot make the round free, and one re-priced in
   * between cannot bill the round at a rate nobody was quoted. The {@code saleUnit}/{@code
   * unitFactor}/{@code billingMode}/{@code discount} defaults still match {@link
   * CafeMenuCartLineContributor#buildMenuLine} exactly, because a flushed line and an added-in-Sell
   * line of the same item must not disagree.
   *
   * <p>The menu lookup survives only as a fallback for a line composed before the price was frozen
   * — one already sitting on a tab, or inside a PENDING flush, when this version deployed. The
   * ERROR below is what is left when even that finds nothing, and for a line composed by this
   * version it is unreachable. It still does not throw: these lines are already claimed, and a
   * throw here would strand the tab with a PENDING flush no retry could ever finish, owing a
   * kitchen that was never told.
   */
  private Document billLine(String shopId, CafeTabLine line) {
    int quantity = line.getQuantity() == null ? 0 : line.getQuantity();
    Document billLine =
        new Document("sellableRef", line.getSellableRef())
            .append("lineRef", line.getLineRef())
            .append("sellMode", SELL_MODE_MENU)
            .append("name", line.getName())
            .append("billingMode", BILLING_MODE_REGULAR)
            .append("quantity", BigDecimal.valueOf(quantity))
            .append("saleUnit", SALE_UNIT_PCS)
            .append("baseQuantity", quantity)
            .append("unitFactor", 1)
            .append("kotSentQuantity", quantity)
            .append("department", MenuDepartments.resolve(line.getDepartment()))
            .append("note", line.getNote());

    CafeTabLine priced = pricedLine(shopId, line);
    if (priced == null || priced.getPrice() == null) {
      log.error(
          "Cafe flush priced line {} ({}) at nothing: no frozen price and no menu item for {} "
              + "in shop {}",
          line.getLineRef(),
          line.getName(),
          line.getSellableRef(),
          shopId);
      return billLine;
    }

    BigDecimal unitPrice = priced.getPrice();
    String cgst = priced.getCgst();
    String sgst = priced.getSgst();
    BigDecimal billableQty = BigDecimal.valueOf(quantity);
    // No discount is composable on a tab, so the additional discount is null here by
    // construction — the argument is kept explicit to match the contributor's call exactly.
    //
    // The rates are NOT passed to the calculator, and that is not an oversight. A menu line's
    // maximumRetailPrice is its selling price, which core reads as "selling at MRP" — so the
    // bill's own arithmetic treats the menu price as tax-inclusive and adds nothing on top
    // (CheckoutUtils.isSellingAtMrp, CheckoutService.calculateTax). Adding tax here would print a
    // line total the bill's stored grandTotal contradicts. The rates still ride onto the line:
    // they are what the menu charged, and the cancel path and the tax view both read them.
    BigDecimal totalAmount =
        CartLineAmountCalculator.lineTotal(unitPrice, null, billableQty, null, null);

    return billLine
        .append("maximumRetailPrice", unitPrice)
        .append("priceToRetail", unitPrice)
        .append("discount", BigDecimal.ZERO)
        .append("totalAmount", totalAmount)
        .append("cgst", cgst)
        .append("sgst", sgst);
  }

  /**
   * The line's own frozen price, or a menu lookup for a line composed before prices were frozen.
   *
   * @return a line carrying a price, or null when neither source has one.
   */
  private CafeTabLine pricedLine(String shopId, CafeTabLine line) {
    if (line.getPrice() != null) {
      return line;
    }
    MenuItem menuItem = findMenuItem(shopId, line);
    if (menuItem == null) {
      return null;
    }
    log.warn(
        "Cafe tab line {} ({}) carries no frozen price; falling back to today's menu for {}",
        line.getLineRef(),
        line.getName(),
        line.getSellableRef());
    CafeTabLine fallback = new CafeTabLine();
    fallback.setPrice(menuItem.getSellingPrice());
    fallback.setCgst(menuItem.getCgst());
    fallback.setSgst(menuItem.getSgst());
    return fallback;
  }

  /** The menu item behind a tab line, or null when the ref is unparseable or the item is gone. */
  private MenuItem findMenuItem(String shopId, CafeTabLine line) {
    SellableRef ref = SellableRef.parseLenient(line.getSellableRef());
    if (ref == null || !ref.isMenu()) {
      return null;
    }
    return shopMenuLookup.findMenuItem(shopId, ref.id()).orElse(null);
  }

  // ------------------------------------------------------------------ step 3

  /**
   * Writes the tickets this flush is missing, and only those.
   *
   * <p>The desired set is filtered against what the repository already holds for this flush
   * <b>before a single sequence number is allocated</b>. Allocating first and discarding the
   * surplus is not good enough: it burns numbers, and it proves nothing about what is written —
   * a {@code saveAll} of an already-written ticket would replace the stored document and renumber
   * paper a cook is holding at the pass, which is exactly what a recovery must never do.
   */
  private List<CafeKot> createTickets(
      String shopId, String userId, Document bill, String flushId, List<CafeTabLine> lines) {

    List<CafeKot> desired = desiredTickets(shopId, userId, bill, flushId, lines);

    List<CafeKot> existing = cafeKotRepository.findByShopIdAndFlushId(shopId, flushId);
    Set<String> alreadyWritten = new LinkedHashSet<>();
    existing.forEach(kot -> alreadyWritten.add(kot.getId()));

    List<CafeKot> missing =
        desired.stream().filter(kot -> !alreadyWritten.contains(kot.getId())).toList();

    // Allocated only for the tickets actually about to be written.
    for (CafeKot kot : missing) {
      kot.setKotNo(cafeSequenceService.allocate(shopId, LocalDate.now(), CafeSequenceSeries.KOT));
    }
    List<CafeKot> created = missing.isEmpty() ? List.of() : cafeKotRepository.saveAll(missing);

    if (!alreadyWritten.isEmpty()) {
      log.warn(
          "Resumed cafe flush {} on shop {}: {} ticket(s) already written, {} created now",
          flushId,
          shopId,
          alreadyWritten.size(),
          created.size());
    }

    if (alreadyWritten.isEmpty()) {
      return created;
    }
    Map<String, CafeKot> byId = new LinkedHashMap<>();
    existing.forEach(kot -> byId.put(kot.getId(), kot));
    created.forEach(kot -> byId.put(kot.getId(), kot));
    return desired.stream().map(kot -> byId.get(kot.getId())).filter(Objects::nonNull).toList();
  }

  /**
   * One ticket per station the claimed lines name, without numbers and without a save.
   *
   * <p>The {@code _id} is {@code {flushId}:{department}:ISSUE}, which is what makes writing one
   * twice a replacement rather than a duplicate. The department is the one frozen onto the line
   * when it was added; {@code MenuDepartments.resolve} is applied to it, which only trims and
   * upper-cases — the menu is never consulted again, so a station renamed since the order was
   * composed cannot reroute a live ticket.
   */
  private static List<CafeKot> desiredTickets(
      String shopId, String userId, Document bill, String flushId, List<CafeTabLine> lines) {

    String purchaseId = String.valueOf(bill.get("_id"));
    int roundNo = roundNo(bill, flushId);
    String businessDate = LocalDate.now().toString();
    Instant now = Instant.now();

    Map<String, CafeKot> byId = new LinkedHashMap<>();
    for (CafeTabLine line : lines) {
      int quantity = line.getQuantity() == null ? 0 : line.getQuantity();
      if (quantity <= 0) {
        continue;
      }
      String department = MenuDepartments.resolve(line.getDepartment());
      String id = flushId + ":" + department + ":" + CafeKotKind.ISSUE.name();

      CafeKot kot =
          byId.computeIfAbsent(
              id,
              key -> {
                CafeKot fresh = new CafeKot();
                fresh.setId(key);
                fresh.setShopId(shopId);
                fresh.setPurchaseId(purchaseId);
                fresh.setDepartment(department);
                fresh.setKind(CafeKotKind.ISSUE);
                fresh.setRoundNo(roundNo);
                fresh.setStatus(CafeKotStatus.ISSUED);
                fresh.setTableLabel(bill.getString("tableLabel"));
                fresh.setTokenNo(bill.getString("tokenNo"));
                fresh.setFlushId(flushId);
                fresh.setBusinessDate(businessDate);
                fresh.setCreatedAt(now);
                fresh.setCreatedBy(userId);
                fresh.setLines(new ArrayList<>());
                return fresh;
              });

      CafeKotLine kotLine = new CafeKotLine();
      kotLine.setLineId(line.getLineRef());
      kotLine.setName(line.getName());
      kotLine.setQuantity(quantity);
      kotLine.setNote(line.getNote());
      kot.getLines().add(kotLine);
    }
    return List.copyOf(byId.values());
  }

  /** The flush's position on the bill, one-based: the round the kitchen reads off the paper. */
  private static int roundNo(Document bill, String flushId) {
    List<?> absorbed = (List<?>) bill.get(FLUSH_IDS);
    if (absorbed != null) {
      int index = absorbed.indexOf(flushId);
      if (index >= 0) {
        return index + 1;
      }
    }
    // The append ran before this and either landed or found the flush already recorded, so the id
    // must be here. Falling back to a guess would hand two rounds the same number instead of
    // surfacing the inconsistency.
    throw new IllegalStateException(
        "Cafe flush " + flushId + " is not recorded on bill " + bill.get("_id"));
  }

  // ------------------------------------------------------------------ step 4

  /**
   * The last step, and the only thing allowed to clear a PENDING record.
   *
   * <p>Matched by {@code flushId} as well as by shop and tab, so a flush that has since been
   * superseded cannot mark somebody else's record complete.
   */
  private void markComplete(String shopId, String tabId, String flushId) {
    Query query =
        Query.query(
            Criteria.where("_id")
                .is(tabId)
                .and("shopId")
                .is(shopId)
                .and("pendingFlush.flushId")
                .is(flushId));
    Update update = new Update().set("pendingFlush.status", CafeFlushStatus.COMPLETE.name());
    long matched = mongoTemplate.updateFirst(query, update, TABS).getMatchedCount();
    if (matched == 0) {
      // The one situation in which the invariant is already broken: this flush's record is no
      // longer on the tab, so somebody replaced it while the work was in flight. The work itself
      // landed — the lines are on the bill and the tickets are written — but the tab now carries
      // a record that nothing will complete. Discarding this result would hide it entirely.
      log.warn(
          "Cafe flush {} found no record to complete on tab {} in shop {}: its pendingFlush was "
              + "replaced while the flush was in flight",
          flushId,
          tabId,
          shopId);
    }
  }

  // ----------------------------------------------------------------- helpers

  /** A new bill's id, derived from the flush that asked for it so a retry cannot open a second. */
  private static String newBillId(String flushId) {
    return "cafe-flush-" + flushId;
  }

  /** This tab's record of an earlier claim under {@code idempotencyKey}, if it still remembers. */
  private static Optional<CafeRecentFlush> recentFlush(CafeTab tab, String idempotencyKey) {
    List<CafeRecentFlush> recent = tab.getRecentFlushKeys();
    if (recent == null) {
      return Optional.empty();
    }
    return recent.stream()
        .filter(entry -> idempotencyKey.equals(entry.getIdempotencyKey()))
        .filter(entry -> StringUtils.hasText(entry.getFlushId()))
        .findFirst();
  }

  private CafeTab requireTab(String shopId, String userId, String tabId) {
    return cafeTabRepository
        .findByIdAndShopIdAndUserId(tabId, shopId, userId)
        .orElseThrow(() -> new ResourceNotFoundException("CafeTab", "tabId", tabId));
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
