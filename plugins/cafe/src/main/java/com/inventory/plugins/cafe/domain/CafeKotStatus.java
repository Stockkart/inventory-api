package com.inventory.plugins.cafe.domain;

/**
 * ISSUED means the backend created the ticket and its document is available for fulfilment — not
 * that paper reached a kitchen. Physical delivery is outside this system's boundary, which is why
 * there is no PREPARING or READY: nothing reports back.
 */
public enum CafeKotStatus {
  ISSUED,
  VOIDED
}
