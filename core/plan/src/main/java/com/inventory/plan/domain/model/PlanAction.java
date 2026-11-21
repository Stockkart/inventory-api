package com.inventory.plan.domain.model;

/** What a plan card offers a shop, decided by the API so apps never compare plans themselves. */
public enum PlanAction {
  /** The shop's own paid plan: the card shows it as current and offers a renewal. */
  CURRENT,
  /** A plan above the shop's paid plan in catalogue order. */
  UPGRADE,
  /** Any other plan: a plan below the paid one, or every plan while the shop has no paid plan. */
  BUY
}
