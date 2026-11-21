package com.inventory.documentservice.api;

import java.util.List;
import lombok.Builder;

/**
 * A kitchen order ticket as plain values, so callers never reach documentservice internals.
 * Rendered by {@link KotDocuments}.
 *
 * <p>No shop, customer, price or tax field, on purpose: a KOT is a production document, not a
 * financial one.
 *
 * @param kotNo the ticket number
 * @param department the station the ticket goes to, e.g. {@code KITCHEN}
 * @param roundNo which press of Print KOT on the bill this ticket came from, one-based
 * @param tableLabel dine-in table; null means none printed
 * @param tokenNo daily order token; null means none printed
 * @param printedAt the time printed on the ticket, already formatted
 * @param stamp normal, reprint or cancellation; null means {@link KotStamp#NONE}
 * @param items the lines
 */
@Builder
public record KotDocument(
    Integer kotNo,
    String department,
    Integer roundNo,
    String tableLabel,
    String tokenNo,
    String printedAt,
    KotStamp stamp,
    List<Item> items) {

  /** One line on the ticket. Deliberately carries no price, tax or total. */
  public record Item(String name, Integer quantity, String note) {}
}
