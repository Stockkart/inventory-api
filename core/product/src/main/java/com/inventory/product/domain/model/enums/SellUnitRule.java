package com.inventory.product.domain.model.enums;

/**
 * How a product may be sold at checkout (derived from GST UQC / packaging catalog).
 */
public enum SellUnitRule {
  /** Sell any whole number of base units (e.g. 5 tablets). */
  FRACTIONAL_BASE,
  /** Sell only whole packs; each pack = unitsPerPack base units (e.g. full bottles). */
  PACK_ONLY
}
