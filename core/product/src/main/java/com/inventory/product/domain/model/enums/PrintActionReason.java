package com.inventory.product.domain.model.enums;

/** Why a print job did not go straight to the bridge. Null when it did. */
public enum PrintActionReason {
  BRIDGE_UNAVAILABLE,
  PRINT_IN_PROGRESS
}
