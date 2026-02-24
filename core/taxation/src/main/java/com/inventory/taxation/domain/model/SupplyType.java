package com.inventory.taxation.domain.model;

/**
 * Classification of outward supply for GSTR-1.
 */
public enum SupplyType {
  /** B2B / SEZ / Deemed export - registered recipient with GSTIN */
  B2B,
  /** B2C large - single invoice above threshold (e.g. 2.5L) */
  B2CL,
  /** B2C small - aggregated by type, place, rate */
  B2CS,
  /** Export */
  EXP
}
