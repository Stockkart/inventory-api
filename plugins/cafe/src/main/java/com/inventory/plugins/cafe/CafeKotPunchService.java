package com.inventory.plugins.cafe;

import com.inventory.common.exception.ResourceNotFoundException;
import com.inventory.common.exception.ValidationException;
import com.inventory.pluginengine.kot.CafeCartPunchPort;
import com.inventory.pluginengine.kot.CafeCartPunchPort.Cart;
import com.inventory.pluginengine.kot.CafeCartPunchPort.Delta;
import com.inventory.pluginengine.kot.CafeCartPunchPort.Punch;
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
import java.util.Set;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
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
 * {@code kotSentQuantity} now equals their {@code baseQuantity}. A second reconcile would
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
 * <p>The cart is a {@code purchases} document, which {@code core/product} owns. Every read and
 * write of it goes through {@link CafeCartPunchPort}; this class never names the collection.
 */
@Service
@Slf4j
public class CafeKotPunchService {

  private final CafeCartPunchPort cartPort;
  private final CafeKotRepository kotRepository;
  private final CafeSequenceService sequenceService;

  public CafeKotPunchService(
      CafeCartPunchPort cartPort,
      CafeKotRepository kotRepository,
      CafeSequenceService sequenceService) {
    this.cartPort = cartPort;
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

    Cart purchase = requirePurchase(shopId, purchaseId);
    Punch punch = findPunch(purchase, idempotencyKey);

    if (punch == null) {
      // No punch with this key yet: claim the cart and reconcile its lines, in one write.
      boolean claimed =
          cartPort.claimAndReconcile(
              shopId, purchaseId, UUID.randomUUID().toString(), idempotencyKey, userId);

      // claimAndReconcile returns false for BOTH "no such cart" and "this key is already punched",
      // and its success does not carry the deltas the pipeline computed server-side. Either way
      // the answer is on the document, so re-read it and look again.
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
            claimed ? "matched" : "matched nothing");
        throw new ValidationException(
            "Punch " + idempotencyKey + " could not be claimed; retry the request");
      }
    }

    String punchId = punch.punchId();
    if (punch.complete()) {
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
      String shopId, String userId, String purchaseId, Cart purchase, Punch punch) {

    String punchId = punch.punchId();
    List<CafeKot> desired = tickets(shopId, userId, purchaseId, purchase, punch);

    // `punch.kotIds` alone is not proof of what is missing: an attempt can die after saveAll (:162
    // as was) but before markComplete, leaving kotIds empty while the ticket already sits in the
    // repository. Trusting kotIds alone there would re-allocate a kotNo for a ticket that already
    // has one and saveAll would overwrite the stored document, renumbering paper a cook is already
    // holding. So the actual repository contents for this punch are the source of truth for what
    // exists; kotIds is consulted too only because it can name tickets a stale read might miss.
    List<CafeKot> existing = kotRepository.findByShopIdAndPunchId(shopId, punchId);
    Set<String> alreadyWritten = new LinkedHashSet<>(punch.kotIds());
    existing.forEach(k -> alreadyWritten.add(k.getId()));

    List<CafeKot> missing = desired.stream().filter(k -> !alreadyWritten.contains(k.getId())).toList();

    for (CafeKot kot : missing) {
      // Allocated only for the tickets actually written: a redrive must not renumber the kitchen's
      // existing paper, and must not burn a number for a ticket it is not printing.
      kot.setKotNo(sequenceService.allocate(shopId, LocalDate.now(), CafeSequenceSeries.KOT));
    }

    List<CafeKot> created = missing.stream().map(this::insertOrReadBack).toList();
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
    cartPort.markPunchComplete(shopId, purchaseId, punchId, kotIds);

    if (alreadyWritten.isEmpty()) {
      return created;
    }
    Map<String, CafeKot> byId = new LinkedHashMap<>();
    existing.forEach(k -> byId.put(k.getId(), k));
    created.forEach(k -> byId.put(k.getId(), k));
    return kotIds.stream().map(byId::get).filter(Objects::nonNull).toList();
  }

  /**
   * Inserts a ticket, or returns the stored one when another recovery of the same punch got there
   * first. Never {@code save}: ticket ids are deterministic ({@code punchId:department:kind}), so
   * a save would replace the stored ticket -- renumbering paper a cook may already hold and
   * resetting its reprint count. The number allocated for the losing insert is simply unused.
   */
  private CafeKot insertOrReadBack(CafeKot kot) {
    try {
      return kotRepository.insert(kot);
    } catch (DuplicateKeyException alreadyWritten) {
      return kotRepository
          .findById(kot.getId())
          .orElseThrow(() -> alreadyWritten);
    }
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
      String shopId, String userId, String purchaseId, Cart purchase, Punch punch) {

    String punchId = punch.punchId();
    String businessDate = LocalDate.now().toString();
    int roundNo = roundNo(purchase, punchId);
    Instant now = Instant.now();

    Map<String, CafeKot> byKey = new LinkedHashMap<>();
    for (Delta delta : punch.deltas()) {
      int quantity = delta.quantity();
      if (quantity == 0) {
        continue;
      }
      CafeKotKind kind = quantity > 0 ? CafeKotKind.ISSUE : CafeKotKind.CANCEL;
      String department = MenuDepartments.resolve(delta.department());
      if (!MenuDepartments.emitsTicket(department)) {
        // Claimed with the rest (kotSentQuantity already advanced), but nothing goes to paper.
        continue;
      }
      String id = punchId + ":" + department + ":" + kind.name();

      CafeKot kot =
          byKey.computeIfAbsent(
              id,
              k -> {
                CafeKot fresh = new CafeKot();
                fresh.setId(k);
                fresh.setShopId(shopId);
                fresh.setPurchaseId(purchaseId);
                fresh.setDepartment(department);
                fresh.setKind(kind);
                fresh.setRoundNo(roundNo);
                fresh.setStatus(CafeKotStatus.ISSUED);
                fresh.setTableLabel(purchase.tableLabel());
                fresh.setTokenNo(purchase.tokenNo());
                fresh.setPunchId(punchId);
                fresh.setBusinessDate(businessDate);
                fresh.setCreatedAt(now);
                fresh.setCreatedBy(userId);
                fresh.setLines(new ArrayList<>());
                return fresh;
              });

      CafeKotLine line = new CafeKotLine();
      line.setLineId(delta.sellableRef());
      line.setName(delta.name());
      // A cancellation tells the station how many to stop, which is never a negative count.
      line.setQuantity(Math.abs(quantity));
      line.setNote(delta.note());
      kot.getLines().add(line);
    }
    return List.copyOf(byKey.values());
  }

  private Cart requirePurchase(String shopId, String purchaseId) {
    return cartPort
        .findCart(shopId, purchaseId)
        .orElseThrow(() -> new ResourceNotFoundException("Purchase", "id", purchaseId));
  }

  private static Punch findPunch(Cart purchase, String idempotencyKey) {
    for (Punch punch : purchase.punches()) {
      if (idempotencyKey.equals(punch.idempotencyKey())) {
        return punch;
      }
    }
    return null;
  }

  /** The punch's position on the cart, one-based: the round the kitchen sees on the paper. */
  private static int roundNo(Cart purchase, String punchId) {
    List<Punch> punches = purchase.punches();
    for (int i = 0; i < punches.size(); i++) {
      if (punchId != null && punchId.equals(punches.get(i).punchId())) {
        return i + 1;
      }
    }
    // The caller always passes the punchId of a punch it just read off this very purchase
    // document, so it must be found above. Silently falling back to punches.size() would hand out
    // a duplicate round number instead of surfacing the inconsistency.
    throw new IllegalStateException(
        "Punch " + punchId + " is not among the punches on purchase " + purchase.id());
  }
}
