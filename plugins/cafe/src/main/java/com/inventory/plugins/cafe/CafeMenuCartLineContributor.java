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
import com.inventory.pluginengine.menu.MenuSellMode;
import com.inventory.pluginengine.ref.SellableRef;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class CafeMenuCartLineContributor implements CartLineContributor {

  private final ShopMenuLookup shopMenuLookup;
  private final InventoryCartLookup inventoryCartLookup;

  public CafeMenuCartLineContributor(
      ShopMenuLookup shopMenuLookup, InventoryCartLookup inventoryCartLookup) {
    this.shopMenuLookup = shopMenuLookup;
    this.inventoryCartLookup = inventoryCartLookup;
  }

  @Override
  public String getVerticalId() {
    return "cafe";
  }

  @Override
  public void validateRequestItems(List<CartLineInput> items) {
    if (items == null || items.isEmpty()) {
      throw new ValidationException("At least one menu item is required");
    }
    for (CartLineInput item : items) {
      SellableRef ref = CartLineRefs.parseRequired(item);
      if (!ref.isMenu()) {
        throw new ValidationException("Cafe cart lines must use menu: sellableRef");
      }
      int qty = item.getQuantity() != null ? item.getQuantity() : 0;
      if (qty == 0) {
        throw new ValidationException("Quantity must be non-zero for menu item: " + ref.id());
      }
    }
  }

  @Override
  public List<CartLineSnapshot> buildLines(List<CartLineInput> items, CartBuildContext context) {
    String shopId = context.getShopId();
    List<CartLineSnapshot> out = new ArrayList<>();
    for (CartLineInput input : items) {
      SellableRef sellable = CartLineRefs.parseRequired(input);
      String menuItemId = sellable.id();
      MenuItem menuItem =
          shopMenuLookup
              .findMenuItem(shopId, menuItemId)
              .orElseThrow(
                  () ->
                      new ResourceNotFoundException(
                          "MenuItem", "sellableRef", sellable.encode()));
      int qty = input.getQuantity();
      CartSellMode sellMode =
          menuItem.getSellMode() == MenuSellMode.direct ? CartSellMode.DIRECT : CartSellMode.MENU;

      if (qty < 0) {
        out.add(
            CartLineSnapshot.builder()
                .sellableRef(sellable.encode())
                .sellMode(sellMode)
                .name(menuItem.getName())
                .billingMode("REGULAR")
                .quantity(BigDecimal.valueOf(qty))
                .saleUnit("PCS")
                .baseQuantity(qty)
                .unitFactor(1)
                .build());
        continue;
      }

      if (!Boolean.TRUE.equals(menuItem.getAvailable())) {
        throw new ValidationException("Menu item is not available: " + menuItem.getName());
      }

      BigDecimal unitPrice = menuItem.getSellingPrice();
      String cgst = menuItem.getCgst();
      String sgst = menuItem.getSgst();
      BigDecimal costPrice = null;
      String stockRef = null;
      BigDecimal mrp = unitPrice;

      if (sellMode == CartSellMode.DIRECT) {
        InventoryLineSnapshot inv =
            inventoryCartLookup
                .findForShop(shopId, menuItem.getInventoryId())
                .orElseThrow(
                    () ->
                        new ValidationException(
                            "Linked inventory not found for menu item: " + menuItem.getName()));
        if (inv.getAvailableBaseCount() < qty) {
          throw new InsufficientStockException(
              "Insufficient stock for: " + menuItem.getName(),
              null,
              inv.getAvailableBaseCount(),
              qty);
        }
        stockRef = SellableRef.inventory(inv.getInventoryId()).encode();
        costPrice = inv.getCostPrice();
        if (inv.getMaximumRetailPrice() != null) {
          mrp = inv.getMaximumRetailPrice();
        }
        if (!StringUtils.hasText(cgst)) {
          cgst = inv.getCgst();
        }
        if (!StringUtils.hasText(sgst)) {
          sgst = inv.getSgst();
        }
      }

      BigDecimal billableQty = BigDecimal.valueOf(qty);
      BigDecimal total =
          CartLineAmountCalculator.lineTotal(
              unitPrice, input.getSaleAdditionalDiscount(), billableQty, cgst, sgst);
      BigDecimal costTotal =
          costPrice != null
              ? costPrice.multiply(billableQty).setScale(2, java.math.RoundingMode.HALF_UP)
              : null;

      out.add(
          CartLineSnapshot.builder()
              .sellableRef(sellable.encode())
              .stockRef(stockRef)
              .sellMode(sellMode)
              .name(menuItem.getName())
              .billingMode("REGULAR")
              .quantity(billableQty)
              .saleUnit("PCS")
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
              .build());
    }
    return out;
  }

  @Override
  public String lineKey(CartLineSnapshot line) {
    return line.getSellableRef();
  }
}
