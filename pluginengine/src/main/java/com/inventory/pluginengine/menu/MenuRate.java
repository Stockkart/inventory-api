package com.inventory.pluginengine.menu;

import java.math.BigDecimal;
import lombok.Data;

/**
 * One portion of a menu item — Qtr, Half, Full — priced in its own right.
 *
 * <p>{@link #id} is the identity and {@link #name} is only ever displayed. The id is a readable
 * slug derived from the name once, at creation, and frozen thereafter: it is persisted inside
 * every {@code sellableRef} the portion is sold under, so making mutable display text part of it
 * would orphan every historical reference the moment a shop renamed "Half" to "1/2". A slug
 * rather than a UUID because that reference surfaces in invoices, logs and the database, where
 * {@code menu:<uuid>@half} is debuggable and {@code menu:<uuid>@a3f91c} is not.
 *
 * <p>There is deliberately no default rate: the Sell screen's picker forces an explicit choice on
 * every sale, so a default would be dead weight with a wrong portion waiting inside it.
 */
@Data
public class MenuRate {

  /** Frozen slug — {@code half}, {@code full}, {@code qtr}, {@code half-2} on a collision. */
  private String id;

  /** Display text, in the shop's own words and casing. Renamed freely; never an identity. */
  private String name;

  private BigDecimal price;
}
