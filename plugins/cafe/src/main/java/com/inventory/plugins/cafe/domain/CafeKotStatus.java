package com.inventory.plugins.cafe.domain;

/**
 * ISSUED means the backend created the ticket and its document is available for fulfilment — not
 * that paper reached a kitchen. Physical delivery is outside this system's boundary, which is why
 * there is no PREPARING or READY: nothing reports back.
 *
 * <p>One constant, because one is all anything writes. A ticket that stops food is an ordinary
 * ISSUED ticket whose {@link CafeKotKind} is CANCEL; the retired punch model's VOIDED state had
 * no writer left on this branch.
 */
public enum CafeKotStatus {
  ISSUED
}
