package com.inventory.plugins.cafe;

import com.inventory.common.exception.ResourceNotFoundException;
import com.inventory.common.exception.ValidationException;
import com.inventory.pluginengine.integration.ShopMenuLookup;
import com.inventory.pluginengine.menu.MenuDepartments;
import com.inventory.pluginengine.menu.MenuItem;
import com.inventory.pluginengine.ref.SellableRef;
import com.inventory.plugins.cafe.domain.CafeTab;
import com.inventory.plugins.cafe.domain.CafeTabLine;
import com.inventory.plugins.cafe.domain.CafeTabRepository;
import com.inventory.plugins.cafe.domain.CafeTabStatus;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Lifecycle for cafe KOT tabs: a party's pending order, holding only what has not yet been sent
 * to the kitchen.
 *
 * <p>Mirrors {@code QuotationService}'s open-quotation rules exactly, because "follow the
 * existing tab behaviour" is the requirement, not a suggestion: scoped to {@code userId} and
 * {@code shopId}; a hard cap of {@value #MAX_OPEN_TABS_PER_USER} open per user, enforced at
 * creation and evicting nothing; no expiry, no TTL, no scheduled cleanup, no auto-close on date
 * rollover; ends only by an explicit close.
 *
 * <p><b>Every edit here is a targeted write</b>, through {@link CafeTabTargetedWriter}, and never
 * {@code save(tab)}. The tab document is shared with {@link CafeTabFlusher}'s claim, which writes
 * the {@code pendingFlush} record that is the only evidence a round has been taken off the tab
 * and is owed to a kitchen not yet told. A full-document replace from a snapshot read before the
 * claim puts the claimed lines back and deletes that record, re-arming the round for a second
 * send. See the writer's javadoc for the whole sequence.
 */
@Service
@Slf4j
public class CafeTabService {

  static final int MAX_OPEN_TABS_PER_USER = 30;

  /** Counter scope distinct from the bill's token ({@link CafeTokenService#SCOPE_BILL}). */
  static final String TOKEN_SCOPE = "TAB";

  private final CafeTabRepository cafeTabRepository;
  private final CafeTokenService cafeTokenService;
  private final ShopMenuLookup shopMenuLookup;
  private final CafeTabTargetedWriter cafeTabTargetedWriter;

  public CafeTabService(
      CafeTabRepository cafeTabRepository,
      CafeTokenService cafeTokenService,
      ShopMenuLookup shopMenuLookup,
      CafeTabTargetedWriter cafeTabTargetedWriter) {
    this.cafeTabRepository = cafeTabRepository;
    this.cafeTokenService = cafeTokenService;
    this.shopMenuLookup = shopMenuLookup;
    this.cafeTabTargetedWriter = cafeTabTargetedWriter;
  }

  /** Opens a new tab for this cashier, failing rather than evicting when the cap is reached. */
  public CafeTab open(String shopId, String userId) {
    long openCount =
        cafeTabRepository.countByShopIdAndUserIdAndStatus(shopId, userId, CafeTabStatus.OPEN);
    if (openCount >= MAX_OPEN_TABS_PER_USER) {
      throw new ValidationException(
          "Maximum open tabs reached (" + MAX_OPEN_TABS_PER_USER + "). Close one to continue.");
    }

    String tokenNo = cafeTokenService.allocateToken(shopId, TOKEN_SCOPE);
    Instant now = Instant.now();

    CafeTab tab = new CafeTab();
    tab.setShopId(shopId);
    tab.setUserId(userId);
    tab.setTokenNo(tokenNo);
    tab.setStatus(CafeTabStatus.OPEN);
    tab.setLines(new ArrayList<>());
    tab.setCreatedAt(now);
    tab.setUpdatedAt(now);

    CafeTab saved = cafeTabRepository.save(tab);
    log.info("Opened cafe tab {} token {} for shop {} user {}", saved.getId(), tokenNo, shopId, userId);
    return saved;
  }

  /** This cashier's open tabs only — never another cashier's, even in the same shop. */
  public List<CafeTab> list(String shopId, String userId) {
    return cafeTabRepository.findByShopIdAndUserIdAndStatusOrderByCreatedAtDesc(
        shopId, userId, CafeTabStatus.OPEN);
  }

  /**
   * Adds one line to an open tab. The department is resolved from the menu item and frozen onto
   * the line now, the same way {@code CafeMenuCartLineContributor} freezes it onto a cart line —
   * a later menu edit must not reroute an order already composed.
   *
   * <p>The price and the two GST rates are frozen with it, and for the same reason: this is the
   * moment the customer is quoted. The flush then bills what was quoted, whatever has happened to
   * the menu in between — a re-price, or the item being deleted outright. See {@link CafeTabLine}.
   */
  public CafeTab addLine(
      String shopId, String userId, String tabId, String sellableRef, int quantity, String note) {
    if (quantity <= 0) {
      throw new ValidationException("Quantity must be positive");
    }
    CafeTab tab = loadOpenTab(shopId, userId, tabId);
    SellableRef ref = SellableRef.parse(sellableRef);
    if (!ref.isMenu()) {
      throw new ValidationException("Tab lines must use menu: sellableRef");
    }
    MenuItem menuItem =
        shopMenuLookup
            .findMenuItem(shopId, ref.id())
            .orElseThrow(
                () -> new ResourceNotFoundException("MenuItem", "sellableRef", sellableRef));

    CafeTabLine line = new CafeTabLine();
    line.setLineRef(UUID.randomUUID().toString());
    line.setSellableRef(ref.encode());
    line.setName(menuItem.getName());
    line.setQuantity(quantity);
    line.setNote(normalizeNote(note));
    line.setDepartment(MenuDepartments.resolve(menuItem.getDepartment()));
    line.setPrice(menuItem.getSellingPrice());
    line.setCgst(menuItem.getCgst());
    line.setSgst(menuItem.getSgst());

    // $push, never a replace of the document this snapshot came from: see
    // CafeTabTargetedWriter. A flush claiming the tab while this item was being composed keeps
    // its pendingFlush record and its emptied lines; this line lands on top of them.
    return applied(
        shopId,
        userId,
        tabId,
        cafeTabTargetedWriter.appendLine(shopId, userId, tabId, line));
  }

  /**
   * Updates the quantity and/or note of an existing line. The frozen department never changes, and
   * neither does the frozen price: the customer was quoted it when the line was composed.
   */
  public CafeTab updateLine(
      String shopId, String userId, String tabId, String lineRef, Integer quantity, String note) {
    CafeTab tab = loadOpenTab(shopId, userId, tabId);
    findLine(tab, lineRef);
    if (quantity != null && quantity <= 0) {
      throw new ValidationException("Quantity must be positive");
    }
    // The one line, by lineRef, through an array filter -- so a round claimed for the kitchen
    // between the read above and this write is not restored by the edit.
    long matched =
        cafeTabTargetedWriter.updateLine(
            shopId, userId, tabId, lineRef, quantity, note != null, normalizeNote(note));
    return applied(shopId, userId, tabId, matched);
  }

  public CafeTab removeLine(String shopId, String userId, String tabId, String lineRef) {
    CafeTab tab = loadOpenTab(shopId, userId, tabId);
    boolean present = tab.getLines().stream().anyMatch(line -> lineRef.equals(line.getLineRef()));
    if (!present) {
      throw new ResourceNotFoundException("CafeTabLine", "lineRef", lineRef);
    }
    // $pull of that one element. If a flush claimed the line in the meantime there is nothing
    // left to pull, and the tab is returned as it now stands rather than being rewound to the
    // snapshot this request read.
    return applied(
        shopId, userId, tabId, cafeTabTargetedWriter.removeLine(shopId, userId, tabId, lineRef));
  }

  /** The only way a tab leaves the open state. */
  public void close(String shopId, String userId, String tabId) {
    loadOpenTab(shopId, userId, tabId);
    // Only status and updatedAt. A tab closed while it still owes the kitchen a flush keeps its
    // pendingFlush record, so the resume path can still finish it.
    if (cafeTabTargetedWriter.close(shopId, userId, tabId) == 0) {
      throw new ValidationException("Tab is no longer open");
    }
    log.info("Closed cafe tab {} for shop {} user {}", tabId, shopId, userId);
  }

  /**
   * The tab as it now stands, read back after a targeted write rather than reconstructed from the
   * snapshot the write was computed from -- which is the whole point: whatever else landed on the
   * document in between belongs in the answer.
   */
  private CafeTab applied(String shopId, String userId, String tabId, long matched) {
    if (matched == 0) {
      throw new ValidationException("Tab is no longer open");
    }
    return cafeTabRepository
        .findByIdAndShopIdAndUserId(tabId, shopId, userId)
        .orElseThrow(() -> new ResourceNotFoundException("CafeTab", "tabId", tabId));
  }

  private CafeTab loadOpenTab(String shopId, String userId, String tabId) {
    CafeTab tab =
        cafeTabRepository
            .findByIdAndShopIdAndUserId(tabId, shopId, userId)
            .orElseThrow(() -> new ResourceNotFoundException("CafeTab", "tabId", tabId));
    if (tab.getStatus() != CafeTabStatus.OPEN) {
      throw new ValidationException("Tab is not open (status: " + tab.getStatus() + ")");
    }
    return tab;
  }

  private static CafeTabLine findLine(CafeTab tab, String lineRef) {
    return tab.getLines().stream()
        .filter(line -> lineRef.equals(line.getLineRef()))
        .findFirst()
        .orElseThrow(() -> new ResourceNotFoundException("CafeTabLine", "lineRef", lineRef));
  }

  /** Blank or whitespace-only becomes null — an empty instruction line is noise on a ticket. */
  private static String normalizeNote(String note) {
    if (note == null) {
      return null;
    }
    String trimmed = note.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }
}
