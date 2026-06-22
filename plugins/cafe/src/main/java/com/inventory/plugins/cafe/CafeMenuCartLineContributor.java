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
import com.inventory.pluginengine.menu.MenuItem;
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
      SellableRef ref = CartLineRefs.parseRequired(item);
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
      SellableRef sellable = CartLineRefs.parseRequired(input);
      int qty = input.getQuantity();
      if (sellable.isMenu()) {
        out.add(buildMenuLine(shopId, sellable, input, qty));
      } else {
        out.add(buildInventoryLine(shopId, sellable, input, qty));
      }
    }
    return out;
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
      return CartLineSnapshot.builder()
          .sellableRef(sellable.encode())
          .sellMode(CartSellMode.MENU)
          .name(menuItem.getName())
          .billingMode("REGULAR")
          .quantity(BigDecimal.valueOf(qty))
          .saleUnit("PCS")
          .baseQuantity(qty)
          .unitFactor(1)
          .build();
    }

    if (!Boolean.TRUE.equals(menuItem.getAvailable())) {
      throw new ValidationException("Menu item is not available: " + menuItem.getName());
    }

    BigDecimal unitPrice = menuItem.getSellingPrice();
    String cgst = menuItem.getCgst();
    String sgst = menuItem.getSgst();
    BigDecimal billableQty = BigDecimal.valueOf(qty);
    BigDecimal total =
        CartLineAmountCalculator.lineTotal(
            unitPrice, input.getSaleAdditionalDiscount(), billableQty, cgst, sgst);

    return CartLineSnapshot.builder()
        .sellableRef(sellable.encode())
        .sellMode(CartSellMode.MENU)
        .name(menuItem.getName())
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
        .build();
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
