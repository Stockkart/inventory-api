package com.inventory.plugins.cafe.domain;

import lombok.Data;

/**
 * A line on the running order. This is the mutable lifecycle record: it is what gets voided, and
 * what settlement filters on. The KOT holds an immutable snapshot of the same line.
 */
@Data
public class CafeOrderLine {

  private String lineId;
  private String sellableRef;
  private String name;
  private Integer quantity;

  /** Free-text preparation note, e.g. "no onion". Printed on the ticket at full body size. */
  private String note;

  /** Resolved from MenuItem.department once, at punch time, then frozen. */
  private String department;

  private String kotId;
  private CafeLineStatus status;
}
