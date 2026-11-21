package com.inventory.plan.domain.model;

public enum AddOnBillingType {
  /** Lasts until the plan term it was bought with ends (§3.3). */
  ANNUAL,
  /** Consumable credits that do not expire (§3.4). */
  ONE_TIME
}
