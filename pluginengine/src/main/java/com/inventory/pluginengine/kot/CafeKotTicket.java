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

  private String businessDate;
  private List<CafeKotTicketLine> lines;
}
