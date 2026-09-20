package com.inventory.pluginengine.order;

import lombok.Builder;
import lombok.Data;

/** A line as the kitchen received it. Never carries price or tax. */
@Data
@Builder
public class KotLineView {

  private String lineId;
  private String name;
  private Integer quantity;
  private String note;
}
