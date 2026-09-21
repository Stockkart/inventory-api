package com.inventory.plugins.cafe.domain;

import lombok.Data;

/**
 * One unsent line on a tab: a menu item, its quantity, an optional preparation note, and the
 * kitchen station frozen at add time via {@code MenuDepartments}. A later menu edit must not
 * reroute an order already composed.
 */
@Data
public class CafeTabLine {

  private String lineRef;
  private String sellableRef;
  private String name;
  private Integer quantity;
  private String note;
  private String department;
}
