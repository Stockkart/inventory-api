package com.inventory.common.util;

import java.util.UUID;

/**
 * Generates the business transaction identifier carried by MIS-visible documents.
 *
 * <p>Deliberately separate from the Mongo {@code _id}: the transaction id is part of the API
 * contract, the {@code _id} is a storage detail. See
 * {@code docs/superpowers/specs/2026-08-02-vendor-mis-txn-id-design.md}.
 */
public final class TxnIdGenerator {

  private TxnIdGenerator() {}

  public static String generate() {
    return UUID.randomUUID().toString();
  }
}
