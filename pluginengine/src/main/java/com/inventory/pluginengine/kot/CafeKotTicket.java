package com.inventory.pluginengine.kot;

import java.util.List;
import lombok.Builder;
import lombok.Data;

/** One kitchen ticket, as core sees it. */
@Data
@Builder
public class CafeKotTicket {

  private String kotId;
  private String shopId;

  /** The purchase (cart) this ticket's punch belongs to. */
  private String purchaseId;

  private Integer kotNo;
  private String department;
  private Integer roundNo;

  /** {@code ISSUE} or {@code CANCEL} — literal, mirroring the cafe-side {@code CafeKotKind}. */
  private String kind;

  /**
   * {@code ISSUED} or {@code VOIDED} — literal, mirroring the cafe-side {@code CafeKotStatus}.
   * Tickets written before {@code kind} existed carry this instead: a ticket voided by the retired
   * running-order path has {@code kind == null} and {@code status == "VOIDED"}.
   */
  private String status;

  /** Dine-in table, free text. Null for tickets with no table (e.g. counter/takeaway). */
  private String tableLabel;

  /** Daily order token. Null when the cart carries none. */
  private String tokenNo;

  private String businessDate;
  private List<CafeKotTicketLine> lines;
}
