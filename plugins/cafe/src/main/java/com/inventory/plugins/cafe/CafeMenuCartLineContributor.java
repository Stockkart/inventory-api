package com.inventory.plugins.cafe;

import com.inventory.common.exception.InsufficientStockException;
import com.inventory.common.exception.ResourceNotFoundException;
import com.inventory.common.exception.ValidationException;
import com.inventory.pluginengine.cart.CartBuildContext;
import com.inventory.pluginengine.cart.CartLineAmountCalculator;
import com.inventory.pluginengine.cart.CartLineContributor;
import com.inventory.pluginengine.cart.CartLineInput;
import com.inventory.pluginengine.cart.CartLineRefs;
import com.inventory.pluginengine.cart.CartLineSnapshot;
import com.inventory.pluginengine.cart.CartSellMode;
import com.inventory.pluginengine.integration.InventoryCartLookup;
import com.inventory.pluginengine.integration.InventoryLineSnapshot;
import com.inventory.pluginengine.integration.ShopMenuLookup;
import com.inventory.pluginengine.menu.MenuDepartments;
import com.inventory.pluginengine.menu.MenuItem;
import com.inventory.pluginengine.menu.MenuRate;
import com.inventory.pluginengine.menu.MenuRates;
import com.inventory.pluginengine.ref.SellableRef;
import com.inventory.plugins.cafe.repository.CafeInventoryExtensionRepository;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class CafeMenuCartLineContributor implements CartLineContributor {

  private final ShopMenuLookup shopMenuLookup;
  private final InventoryCartLookup inventoryCartLookup;
  private final CafeInventoryExtensionRepository extensionRepository;

  public CafeMenuCartLineContributor(
      ShopMenuLookup shopMenuLookup,
      InventoryCartLookup inventoryCartLookup,
      CafeInventoryExtensionRepository extensionRepository) {
    this.shopMenuLookup = shopMenuLookup;
    this.inventoryCartLookup = inventoryCartLookup;
    this.extensionRepository = extensionRepository;
  }

  @Override
  public String getVerticalId() {
    return "cafe";
  }

  @Override
  public void validateRequestItems(List<CartLineInput> items) {
    if (items == null || items.isEmpty()) {
      throw new ValidationException("At least one cart item is required");
    }
    for (CartLineInput item : items) {
      SellableRef ref = parseRef(item);
      if (!ref.isMenu() && !ref.isInventory()) {
        throw new ValidationException(
            "Cafe cart lines must use menu: or inventory: sellableRef");
      }
      int qty = item.getQuantity() != null ? item.getQuantity() : 0;
      if (qty == 0) {
        throw new ValidationException("Quantity must be non-zero for: " + ref.encode());
      }
    }
  }

  @Override
  public List<CartLineSnapshot> buildLines(List<CartLineInput> items, CartBuildContext context) {
    String shopId = context.getShopId();
    List<CartLineSnapshot> out = new ArrayList<>();
    for (CartLineInput input : items) {
      SellableRef sellable = parseRef(input);
      int qty = input.getQuantity();
      if (sellable.isMenu()) {
        out.add(buildMenuLine(shopId, sellable, input, qty));
      } else {
        out.add(buildInventoryLine(shopId, sellable, input, qty));
      }
    }
    return out;
  }

  /**
   * A malformed ref is the cashier's request, not a server fault: it must come back as a 400 that
   * names what was wrong, rather than the 500 an unhandled {@link IllegalArgumentException}
   * becomes. What it must never do is parse into a different portion than the one asked for,
   * which is {@link SellableRef#parse}'s job to refuse.
   */
  private static SellableRef parseRef(CartLineInput input) {
    try {
      return CartLineRefs.parseRequired(input);
    } catch (IllegalArgumentException ex) {
      throw new ValidationException(ex.getMessage());
    }
  }

  /**
   * The line's display name: the dish on an unportioned item, {@code Butter Chicken (Half)} on a
   * portion. Snapshotted onto the cart line, so the kitchen ticket reads
   * {@code 1 x Butter Chicken (Half)} with no template change, and the invoice keeps that wording
   * however the menu is edited afterwards.
   */
  private static String displayName(MenuItem menuItem, MenuRate rate) {
    return rate == null ? menuItem.getName() : menuItem.getName() + " (" + rate.getName() + ")";
  }

  private CartLineSnapshot buildMenuLine(
      String shopId, SellableRef sellable, CartLineInput input, int qty) {
    String menuItemId = sellable.id();
    MenuItem menuItem =
        shopMenuLookup
            .findMenuItem(shopId, menuItemId)
            .orElseThrow(
                () ->
                    new ResourceNotFoundException(
                        "MenuItem", "sellableRef", sellable.encode()));

    if (qty < 0) {
      // A takeback names a line that is already on the bill, matched by this very ref. It must
      // stay possible after the portion has been deleted from the menu -- refusing here would
      // leave a cashier unable to remove a line they can see -- so the portion is resolved only
      // to label the snapshot, never required.
      MenuRate removed = MenuRates.findById(menuItem, sellable.variant()).orElse(null);
      return CartLineSnapshot.builder()
          .sellableRef(sellable.encode())
          .sellMode(CartSellMode.MENU)
          .name(displayName(menuItem, removed))
          .billingMode("REGULAR")
          .quantity(BigDecimal.valueOf(qty))
          .saleUnit("PCS")
          .baseQuantity(qty)
          .unitFactor(1)
          .department(MenuDepartments.resolve(menuItem.getDepartment()))
          .note(normalizeNote(input.getNote()))
          .build();
    }

    if (!Boolean.TRUE.equals(menuItem.getAvailable())) {
      throw new ValidationException("Menu item is not available: " + menuItem.getName());
    }

    MenuRate rate = resolveRate(menuItem, sellable);
    // Always from the menu, never from the request: a client that could name the price could buy
    // a Full portion for the price of a Qtr.
    BigDecimal unitPrice = rate != null ? rate.getPrice() : menuItem.getSellingPrice();
    String cgst = menuItem.getCgst();
    String sgst = menuItem.getSgst();
    BigDecimal billableQty = BigDecimal.valueOf(qty);
    BigDecimal total =
        CartLineAmountCalculator.lineTotal(
            unitPrice, input.getSaleAdditionalDiscount(), billableQty, cgst, sgst);

    return CartLineSnapshot.builder()
        .sellableRef(sellable.encode())
        .sellMode(CartSellMode.MENU)
        .name(displayName(menuItem, rate))
        .billingMode("REGULAR")
        .quantity(billableQty)
        .saleUnit("PCS")
        .baseQuantity(qty)
        .unitFactor(1)
        .maximumRetailPrice(unitPrice)
        .priceToRetail(unitPrice)
        .discount(BigDecimal.ZERO)
        .saleAdditionalDiscount(input.getSaleAdditionalDiscount())
        .totalAmount(total)
        .cgst(cgst)
        .sgst(sgst)
        .department(MenuDepartments.resolve(menuItem.getDepartment()))
        .note(normalizeNote(input.getNote()))
        .build();
  }

  /**
   * The portion this sale is for, or null for an item that has none.
   *
   * <p>Three cases, and the two refusals are the point of it: a portioned item whose ref names no
   * portion cannot be priced at all, and a ref naming a portion the item does not have must not
   * fall back to the first one -- selling a different portion than the one the guest asked for is
   * worse than refusing the sale, and it is what a deleted portion would otherwise do silently.
   */
  private static MenuRate resolveRate(MenuItem menuItem, SellableRef sellable) {
    if (!MenuRates.isPortioned(menuItem)) {
      if (sellable.hasVariant()) {
        // The item's portions were all removed since this screen was loaded. Pricing it from the
        // single price under a portion name the menu no longer has is the same silent mis-sale as
        // falling back to the first rate.
        throw new ValidationException(
            "No portion \"" + sellable.variant() + "\" on " + menuItem.getName()
                + " any more; reload the menu");
      }
      return null;
    }
    if (!sellable.hasVariant()) {
      throw new ValidationException("Choose a portion for " + menuItem.getName());
    }
    return MenuRates.findById(menuItem, sellable.variant())
        .orElseThrow(
            () ->
                new ValidationException(
                    "No portion \""
                        + sellable.variant()
                        + "\" on "
                        + menuItem.getName()
                        + " any more; reload the menu"));
  }

  /** Blank or whitespace-only becomes null — an empty instruction line is noise on a ticket. */
  private static String normalizeNote(String note) {
    if (note == null) {
      return null;
    }
    String trimmed = note.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }

  private CartLineSnapshot buildInventoryLine(
      String shopId, SellableRef sellable, CartLineInput input, int qty) {
    String inventoryId = sellable.id();
    requireSellDirect(shopId, inventoryId);

    if (qty < 0) {
      return CartLineSnapshot.builder()
          .sellableRef(sellable.encode())
          .stockRef(sellable.encode())
          .sellMode(CartSellMode.SKU)
          .quantity(BigDecimal.valueOf(qty))
          .baseQuantity(qty)
          .unitFactor(1)
          .build();
    }

    InventoryLineSnapshot inv =
        inventoryCartLookup
            .findForShop(shopId, inventoryId)
            .orElseThrow(
                () ->
                    new ValidationException(
                        "Inventory not found or not sellable: " + inventoryId));
    if (inv.getAvailableBaseCount() < qty) {
      throw new InsufficientStockException(
          "Insufficient stock for: " + inv.getName(),
          null,
          inv.getAvailableBaseCount(),
          qty);
    }

    BigDecimal unitPrice =
        inv.getPriceToRetail() != null ? inv.getPriceToRetail() : BigDecimal.ZERO;
    if (unitPrice.compareTo(BigDecimal.ZERO) <= 0) {
      throw new ValidationException(
          "Selling price is required for direct-sell item: " + inv.getName());
    }
    BigDecimal mrp =
        inv.getMaximumRetailPrice() != null ? inv.getMaximumRetailPrice() : unitPrice;
    String cgst = inv.getCgst();
    String sgst = inv.getSgst();
    BigDecimal costPrice = inv.getCostPrice();
    BigDecimal billableQty = BigDecimal.valueOf(qty);
    BigDecimal total =
        CartLineAmountCalculator.lineTotal(
            unitPrice, input.getSaleAdditionalDiscount(), billableQty, cgst, sgst);
    BigDecimal costTotal =
        costPrice != null
            ? costPrice.multiply(billableQty).setScale(2, java.math.RoundingMode.HALF_UP)
            : null;

    String saleUnit = resolveInventorySaleUnit(inv);

    return CartLineSnapshot.builder()
        .sellableRef(sellable.encode())
        .stockRef(SellableRef.inventory(inv.getInventoryId()).encode())
        .sellMode(CartSellMode.SKU)
        .name(inv.getName())
        .billingMode("REGULAR")
        .quantity(billableQty)
        .saleUnit(saleUnit)
        .baseUnit(saleUnit)
        .baseQuantity(qty)
        .unitFactor(1)
        .maximumRetailPrice(mrp)
        .priceToRetail(unitPrice)
        .discount(BigDecimal.ZERO)
        .saleAdditionalDiscount(input.getSaleAdditionalDiscount())
        .totalAmount(total)
        .cgst(cgst)
        .sgst(sgst)
        .costPrice(costPrice)
        .costTotal(costTotal)
        .build();
  }

  private void requireSellDirect(String shopId, String inventoryId) {
    Map<String, Object> ext =
        extensionRepository
            .findByInventoryId(shopId, inventoryId)
            .orElseThrow(
                () ->
                    new ValidationException(
                        "Item is not marked for direct sell: " + inventoryId));
    Object flag = ext.get("sellDirect");
    boolean sellDirect =
        flag instanceof Boolean b
            ? b
            : "yes".equalsIgnoreCase(String.valueOf(flag).trim())
                || "true".equalsIgnoreCase(String.valueOf(flag).trim());
    if (!sellDirect) {
      throw new ValidationException(
          "Item is not marked for direct sell. Enable \"Sell directly\" at registration.");
    }
  }

  /** Direct-sell adds one base unit per click (matches stock shown on ingredient search). */
  private static String resolveInventorySaleUnit(InventoryLineSnapshot inv) {
    if (inv.getBaseUnit() != null && !inv.getBaseUnit().isBlank()) {
      return inv.getBaseUnit().trim().toUpperCase();
    }
    return "UNIT";
  }

  @Override
  public String lineKey(CartLineSnapshot line) {
    return line.getSellableRef();
  }
}
