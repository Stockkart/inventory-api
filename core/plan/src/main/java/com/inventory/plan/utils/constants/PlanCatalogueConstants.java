package com.inventory.plan.utils.constants;

/**
 * Constants for the Starter / Professional / Enterprise catalogue.
 */
public final class PlanCatalogueConstants {

  private PlanCatalogueConstants() {}

  /** Plan a shop without a paid plan (trial) is measured against. */
  public static final String TRIAL_PLAN_CODE = "STARTER";

  /** Pre-catalogue trial plan, used until the catalogue is seeded. */
  public static final String LEGACY_TRIAL_PLAN_NAME = "Base";

  /** Classpath location of the catalogue seed. */
  public static final String SEED_RESOURCE = "classpath:plan-catalogue.json";

  /** Classpath location of the add-on catalogue seed. */
  public static final String ADDON_SEED_RESOURCE = "classpath:addon-catalogue.json";
}
