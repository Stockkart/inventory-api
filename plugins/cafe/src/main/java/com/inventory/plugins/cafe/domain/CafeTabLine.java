package com.inventory.plugins.cafe.domain;

import java.math.BigDecimal;
import lombok.Data;

/**
 * One unsent line on a tab: a menu item, its quantity, an optional preparation note, and — frozen
 * at the moment the line is composed — the kitchen station, the price and the two GST rates.
 *
 * <p>Everything the flush needs to bill this line is on the line. Nothing is looked up again when
 * it is sent, and that is the point three times over:
 *
 * <ul>
 *   <li>A menu item <b>deleted</b> between composing and sending still bills at what the customer
 *       was quoted. Looking it up at flush time would find nothing, and a line with no price is a
 *       line the shop gives away — the lines are already claimed by then, so there is nothing to
 *       refuse and nobody to ask.
 *   <li>A menu item <b>re-priced</b> mid-round still bills at the rate the customer was quoted,
 *       not the one the manager typed while the round was being composed.
 *   <li>A <b>renamed station</b> cannot reroute an order already composed, which is why
 *       {@code department} was frozen here first.
 * </ul>
 */
@Data
public class CafeTabLine {

  private String lineRef;
  private String sellableRef;
  private String name;
  private Integer quantity;
  private String note;
  private String department;

  /** The menu's selling price at compose time — what the customer was quoted. */
  private BigDecimal price;

  /** The CGST rate as the menu carried it at compose time, as a percent string. */
  private String cgst;

  /** The SGST rate as the menu carried it at compose time, as a percent string. */
  private String sgst;
}
