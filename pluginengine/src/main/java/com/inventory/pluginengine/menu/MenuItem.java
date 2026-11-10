package com.inventory.pluginengine.menu;

import java.math.BigDecimal;
import java.util.List;
import lombok.Data;

@Data
public class MenuItem {

  private String id;
  private String name;
  private BigDecimal sellingPrice;
  private MenuSellMode sellMode;
  private String inventoryId;
  private Boolean available;
  private String cgst;
  private String sgst;

  /**
   * Portions this dish is sold in — Qtr, Half, Full — each with its own price.
   *
   * <p>When non-empty, {@link #sellingPrice} is null: an item has either a single price or a list
   * of portions, never both in play, and the single price is normalised away at write rather than
   * merely ignored at read, so that no future reader trusts a number nothing honours. A cart line
   * for a portioned item must name its portion in the {@code sellableRef}'s variant.
   *
   * <p>Null or empty on every item that has a single price, which behaves exactly as it always
   * has. Nothing is migrated.
   */
  private List<MenuRate> rates;

  /**
   * Kitchen routing department. Null means {@link MenuDepartments#DEFAULT}. Resolved at punch
   * time and then frozen onto the order line and ticket.
   */
  private String department;
}
