package com.inventory.pluginengine.kot;

import lombok.Builder;
import lombok.Data;

/** One unsent line on a tab. Never carries price or tax — a tab is not a bill. */
@Data
@Builder
public class CafeKotTabLine {

  private String lineRef;
  private String sellableRef;
  private String name;
  private Integer quantity;
  private String note;

  /** The kitchen station, frozen onto the line when it was added. */
  private String department;
}
