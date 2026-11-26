package com.inventory.plan.utils.constants;

/** Kinds of row in a shop's purchase history. */
public final class PurchaseKindConstants {

  private PurchaseKindConstants() {}

  public static final String PLAN = "PLAN";
  public static final String ADD_ON_ONLY = "ADD_ON_ONLY";
  /** Set by an admin or the assign endpoint; no verified charge. */
  public static final String ASSIGNED = "ASSIGNED";
}
