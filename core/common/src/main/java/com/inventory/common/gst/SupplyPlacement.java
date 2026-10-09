package com.inventory.common.gst;

/**
 * Where each party to a supply is, and so whether the supply crosses a state border (IGST) or not
 * (CGST + SGST). One definition for purchases, purchase returns, sales and the GST returns, so the
 * invoice, the ledger and the return can never disagree.
 *
 * <p>Declared here so every module can ask; implemented in the taxation module, which can see the
 * shop, vendor and customer records and the GSTIN registry.
 */
public interface SupplyPlacement {

  /** Where the shop is registered. */
  PartyPlace placeShop(String shopId);

  /** Where a supplier is: GSTIN registry, then the GSTIN itself, then the state on their address. */
  PartyPlace placeVendor(String vendorId);

  /** Where a customer is, the same way. */
  PartyPlace placeCustomer(String customerId);

  /**
   * Whether goods bought from this supplier cross a state border. Both parties must be placed;
   * anything unplaceable is local — the common case, and the safer mistake.
   */
  default boolean isInterstatePurchase(String shopId, String vendorId) {
    return PartyPlace.interstate(placeShop(shopId), placeVendor(vendorId));
  }

  /** Whether a sale to this customer crosses a state border. */
  default boolean isInterstateSale(String shopId, String customerId) {
    return PartyPlace.interstate(placeShop(shopId), placeCustomer(customerId));
  }

  /** A party's state and how it was found. */
  record PartyPlace(String stateCode, Source source) {

    public enum Source {
      /** The GST network's record of the party's GSTIN. */
      GSTIN_REGISTRY,
      /** The first two characters of the party's GSTIN. */
      GSTIN,
      /** The state code on the party's address. */
      ADDRESS_STATE,
      /** A state name found in a free-text address (legacy records). */
      ADDRESS_TEXT,
      /** Nothing on record says where the party is. */
      UNKNOWN
    }

    public static final PartyPlace UNKNOWN = new PartyPlace("", Source.UNKNOWN);

    public static PartyPlace of(String stateCode, Source source) {
      return stateCode == null || stateCode.isBlank() ? UNKNOWN : new PartyPlace(stateCode, source);
    }

    public boolean isKnown() {
      return source != Source.UNKNOWN;
    }

    public static boolean interstate(PartyPlace a, PartyPlace b) {
      return a.isKnown() && b.isKnown() && !a.stateCode().equals(b.stateCode());
    }
  }
}
