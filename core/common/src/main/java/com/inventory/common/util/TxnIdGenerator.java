package com.inventory.common.util;

import java.util.UUID;

/**
 * Allocates system-wide unique transaction ids for money documents (invoices, sales, refunds,
 * credit entries, etc.).
 */
public final class TxnIdGenerator {

  private TxnIdGenerator() {}

  /** Returns a new UUID string unique across the system. */
  public static String newId() {
    return UUID.randomUUID().toString();
  }
}
