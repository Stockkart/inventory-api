package com.inventory.plugins.cafe.domain;

/**
 * CLAIMED means the punch was claimed but its tickets may be incomplete — a previous attempt died
 * between claiming and finishing. COMPLETE means every ticket exists and the order carries the
 * lines.
 */
public enum CafePunchStatus {
  CLAIMED,
  COMPLETE
}
