package com.inventory.product.service.creditnote;

/**
 * Counterparty role on a printable credit note relative to the issuing shop.
 */
public enum CreditNotePartyRole {
  CUSTOMER,
  VENDOR;

  public String wireValue() {
    return name();
  }
}
