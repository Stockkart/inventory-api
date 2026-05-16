package com.inventory.accounting.domain.model;

/**
 * Sub-ledger party against which a journal line is posted (used with control accounts like Sundry
 * Debtors / Sundry Creditors). {@code SHOP} is reserved for shop-level/own postings.
 */
public enum PartyType {
  CUSTOMER,
  VENDOR,
  SHOP
}
