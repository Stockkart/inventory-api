package com.inventory.user.domain.model.enums;

/**
 * Buyer party classification for CRM / Scan &amp; Sell.
 * Non-{@link #CONSUMER} types require at least one unique identifier
 * (phone, email, GSTIN, PAN, or DL).
 */
public enum CustomerPartyType {
  CONSUMER,
  RETAILER,
  DISTRIBUTOR,
  WHOLESALER
}
