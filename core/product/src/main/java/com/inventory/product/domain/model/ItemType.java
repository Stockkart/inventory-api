package com.inventory.product.domain.model;

/**
 * Type of inventory item: normal, costly, or degree-based (e.g. 8 deg, 24 deg).
 * When DEGREE, use itemTypeDegree for the numeric value.
 */
public enum ItemType {
  NORMAL,
  COSTLY,
  DEGREE
}
