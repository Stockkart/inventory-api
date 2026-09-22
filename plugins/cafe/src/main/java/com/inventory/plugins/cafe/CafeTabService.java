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

  public CafeTabService(
      CafeTabRepository cafeTabRepository,
      CafeTokenService cafeTokenService,
      ShopMenuLookup shopMenuLookup) {
    this.cafeTabRepository = cafeTabRepository;
    this.cafeTokenService = cafeTokenService;
    this.shopMenuLookup = shopMenuLookup;
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

    tab.getLines().add(line);
    tab.setUpdatedAt(Instant.now());
    return cafeTabRepository.save(tab);
  }

  /**
   * Updates the quantity and/or note of an existing line. The frozen department never changes, and
   * neither does the frozen price: the customer was quoted it when the line was composed.
   */
  public CafeTab updateLine(
      String shopId, String userId, String tabId, String lineRef, Integer quantity, String note) {
    CafeTab tab = loadOpenTab(shopId, userId, tabId);
    CafeTabLine line = findLine(tab, lineRef);
    if (quantity != null) {
      if (quantity <= 0) {
        throw new ValidationException("Quantity must be positive");
      }
      line.setQuantity(quantity);
    }
    if (note != null) {
      line.setNote(normalizeNote(note));
    }
    tab.setUpdatedAt(Instant.now());
    return cafeTabRepository.save(tab);
  }

  public CafeTab removeLine(String shopId, String userId, String tabId, String lineRef) {
    CafeTab tab = loadOpenTab(shopId, userId, tabId);
    boolean removed = tab.getLines().removeIf(line -> lineRef.equals(line.getLineRef()));
    if (!removed) {
      throw new ResourceNotFoundException("CafeTabLine", "lineRef", lineRef);
    }
    tab.setUpdatedAt(Instant.now());
    return cafeTabRepository.save(tab);
  }

  /** The only way a tab leaves the open state. */
  public void close(String shopId, String userId, String tabId) {
    CafeTab tab = loadOpenTab(shopId, userId, tabId);
    tab.setStatus(CafeTabStatus.CLOSED);
    tab.setUpdatedAt(Instant.now());
    cafeTabRepository.save(tab);
    log.info("Closed cafe tab {} for shop {} user {}", tabId, shopId, userId);
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
