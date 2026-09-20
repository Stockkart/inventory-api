package com.inventory.pluginengine.order;

import lombok.Builder;
import lombok.Data;

/** One line on a running order, as core sees it. */
@Data
@Builder
public class RunningOrderLineView {

  private String lineId;
  private String sellableRef;
  private String name;
  private Integer quantity;
  private String note;
  private String department;
  private String kotId;

  /** ACTIVE or VOIDED. Only ACTIVE lines reach settlement. */
  private String status;
}
