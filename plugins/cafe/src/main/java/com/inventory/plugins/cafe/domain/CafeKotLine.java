package com.inventory.plugins.cafe.domain;

import lombok.Data;

/** An immutable snapshot of an order line as the kitchen received it. */
@Data
public class CafeKotLine {

  private String lineId;
  private String name;
  private Integer quantity;
  private String note;
}
