package com.inventory.taxation.supply;

import com.inventory.common.gst.Gstin;
import com.inventory.common.gst.GstinDirectory;
import com.inventory.common.gst.GstinRegistration;
import com.inventory.common.gst.PostalAddress;
import com.inventory.common.gst.SupplyPlacement;
import com.inventory.common.util.GstStateCode;
import com.inventory.product.domain.model.Shop;
import com.inventory.product.domain.repository.ShopRepository;
import com.inventory.user.domain.model.Customer;
import com.inventory.user.domain.model.Vendor;
import com.inventory.user.domain.repository.CustomerRepository;
import com.inventory.user.domain.repository.VendorRepository;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * The one place that says which state a party is in.
 *
 * <p>Order, for a vendor or customer: what the GST network recorded for their GSTIN → the state
 * code in the GSTIN itself → the state on their address → (legacy records only) a state name
 * found in the free-text address. For the shop: its GSTIN → the state on its address. Nothing
 * found means {@link PartyPlace#UNKNOWN}, and an unknown party makes a supply local.
 */
@Slf4j
@Service
public class SupplyPlacementService implements SupplyPlacement {

  private final ShopRepository shops;
  private final VendorRepository vendors;
  private final CustomerRepository customers;
  private final GstinDirectory gstins;

  public SupplyPlacementService(
      ShopRepository shops, VendorRepository vendors, CustomerRepository customers, GstinDirectory gstins) {
    this.shops = shops;
    this.vendors = vendors;
    this.customers = customers;
    this.gstins = gstins;
  }

  @Override
  public PartyPlace placeShop(String shopId) {
    if (!StringUtils.hasText(shopId)) {
      return PartyPlace.UNKNOWN;
    }
    try {
      Shop shop = shops.findById(shopId.trim()).orElse(null);
      if (shop == null) {
        return PartyPlace.UNKNOWN;
      }
      String fromGstin = GstStateCode.codeFromGstin(shop.getGstinNo());
      if (StringUtils.hasText(fromGstin)) {
        return PartyPlace.of(fromGstin, PartyPlace.Source.GSTIN);
      }
      String fromAddress =
          shop.getLocation() == null ? "" : GstStateCode.codeFromName(shop.getLocation().getState());
      return PartyPlace.of(fromAddress, PartyPlace.Source.ADDRESS_STATE);
    } catch (RuntimeException e) {
      log.warn("[placement] could not place shop {}: {}", shopId, e.getMessage());
      return PartyPlace.UNKNOWN;
    }
  }

  @Override
  public PartyPlace placeVendor(String vendorId) {
    if (!StringUtils.hasText(vendorId)) {
      return PartyPlace.UNKNOWN;
    }
    try {
      Vendor v = vendors.findById(vendorId.trim()).orElse(null);
      if (v == null) {
        return PartyPlace.UNKNOWN;
      }
      return place(v.getGstinUin(), v.getPostalAddress(), v.getAddress());
    } catch (RuntimeException e) {
      log.warn("[placement] could not place vendor {}: {}", vendorId, e.getMessage());
      return PartyPlace.UNKNOWN;
    }
  }

  @Override
  public PartyPlace placeCustomer(String customerId) {
    if (!StringUtils.hasText(customerId)) {
      return PartyPlace.UNKNOWN;
    }
    try {
      Customer c = customers.findById(customerId.trim()).orElse(null);
      if (c == null) {
        return PartyPlace.UNKNOWN;
      }
      return place(c.getGstin(), null, c.getAddress());
    } catch (RuntimeException e) {
      log.warn("[placement] could not place customer {}: {}", customerId, e.getMessage());
      return PartyPlace.UNKNOWN;
    }
  }

  /** The shared rule for any party with an optional GSTIN and an optional address. */
  PartyPlace place(String gstinRaw, PostalAddress address, String addressText) {
    Optional<Gstin> gstin = Gstin.parse(gstinRaw);
    if (gstin.isPresent()) {
      Optional<GstinRegistration> onRecord = gstins.find(gstin.get().value());
      if (onRecord.isPresent() && StringUtils.hasText(onRecord.get().stateCode())) {
        return PartyPlace.of(onRecord.get().stateCode(), PartyPlace.Source.GSTIN_REGISTRY);
      }
      return PartyPlace.of(gstin.get().stateCode(), PartyPlace.Source.GSTIN);
    }
    // a GSTIN that fails the check is ignored rather than trusted for its first two digits
    if (address != null && address.hasState()) {
      return PartyPlace.of(address.getStateCode(), PartyPlace.Source.ADDRESS_STATE);
    }
    String fromText = GstStateCode.codeFromAddress(addressText);
    return PartyPlace.of(fromText, PartyPlace.Source.ADDRESS_TEXT);
  }
}
