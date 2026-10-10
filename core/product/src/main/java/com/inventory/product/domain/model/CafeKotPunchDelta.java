package com.inventory.product.domain.model;

import lombok.Data;

/** One line's change at reconcile time. A negative quantity means cancel that many. */
@Data
public class CafeKotPunchDelta {
  private String sellableRef;
  private String name;
  private String department;
  private Integer quantity;
  private String note;
}
