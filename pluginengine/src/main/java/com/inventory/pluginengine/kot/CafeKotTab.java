package com.inventory.pluginengine.kot;

import java.util.List;
import lombok.Builder;
import lombok.Data;

/** One cafe KOT tab, as core sees it: a party's order not yet sent to the kitchen. */
@Data
@Builder
public class CafeKotTab {

  private String id;

  /** Allocated from the daily per-shop token counter; carried as a string, like a bill's token. */
  private String tokenNo;

  /** {@code OPEN} or {@code CLOSED} — literal, mirroring the cafe-side {@code CafeTabStatus}. */
  private String status;

  private List<CafeKotTabLine> lines;
}
