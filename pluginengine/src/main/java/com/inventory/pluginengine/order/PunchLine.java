package com.inventory.pluginengine.order;

import lombok.Builder;
import lombok.Data;

/** One requested line in a punch. */
@Data
@Builder
public class PunchLine {

  private String sellableRef;
  private Integer quantity;
  private String note;
}
