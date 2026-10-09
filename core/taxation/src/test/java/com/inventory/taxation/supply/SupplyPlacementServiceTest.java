package com.inventory.taxation.supply;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.inventory.common.gst.GstinDirectory;
import com.inventory.common.gst.GstinRegistration;
import com.inventory.common.gst.PostalAddress;
import com.inventory.common.gst.SupplyPlacement.PartyPlace;
import com.inventory.product.domain.model.Location;
import com.inventory.product.domain.model.Shop;
import com.inventory.product.domain.repository.ShopRepository;
import com.inventory.user.domain.model.Vendor;
import com.inventory.user.domain.repository.CustomerRepository;
import com.inventory.user.domain.repository.VendorRepository;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SupplyPlacementServiceTest {

  @Mock ShopRepository shops;
  @Mock VendorRepository vendors;
  @Mock CustomerRepository customers;
  @Mock GstinDirectory gstins;
  @InjectMocks SupplyPlacementService placement;

  @BeforeEach
  void shopInBihar() {
    Shop shop = new Shop();
    shop.setShopId("s1");
    shop.setGstinNo("10AFBPL7000H1Z8");
    when(shops.findById("s1")).thenReturn(Optional.of(shop));
    when(gstins.find(anyString())).thenReturn(Optional.empty());
  }

  private Vendor vendor(String gstin, PostalAddress address, String text) {
    Vendor v = new Vendor();
    v.setId("v1");
    v.setGstinUin(gstin);
    v.setPostalAddress(address);
    v.setAddress(text);
    when(vendors.findById("v1")).thenReturn(Optional.of(v));
    return v;
  }

  @Test
  void registryRecordWinsOverEverything() {
    vendor("27AAPFU0939F1ZV", PostalAddress.builder().stateCode("10").build(), null);
    when(gstins.find("27AAPFU0939F1ZV")).thenReturn(Optional.of(
        new GstinRegistration("27AAPFU0939F1ZV", "X", null, "Active", "Regular", "27", null, null, null, null, null, Instant.now())));
    PartyPlace place = placement.placeVendor("v1");
    assertEquals("27", place.stateCode());
    assertEquals(PartyPlace.Source.GSTIN_REGISTRY, place.source());
    assertTrue(placement.isInterstatePurchase("s1", "v1"));
  }

  @Test
  void validGstinWithoutARecordIsReadForItsState() {
    vendor("27AAPFU0939F1ZV", null, null);
    PartyPlace place = placement.placeVendor("v1");
    assertEquals(new PartyPlace("27", PartyPlace.Source.GSTIN), place);
  }

  @Test
  void unregisteredVendorIsPlacedByTheStateOnTheirAddress() {
    vendor(null, PostalAddress.builder().stateCode("10").build(), "Patna");
    assertEquals(new PartyPlace("10", PartyPlace.Source.ADDRESS_STATE), placement.placeVendor("v1"));
    assertFalse(placement.isInterstatePurchase("s1", "v1"));
  }

  @Test
  void legacyFreeTextAddressIsTheLastResort() {
    vendor(null, null, "12 MG Road, Bengaluru, Karnataka 560001");
    assertEquals(new PartyPlace("29", PartyPlace.Source.ADDRESS_TEXT), placement.placeVendor("v1"));
    assertTrue(placement.isInterstatePurchase("s1", "v1"));
  }

  @Test
  void aMistypedGstinIsNotTrustedForItsFirstTwoDigits() {
    vendor("27AAPFU0939F1ZW", PostalAddress.builder().stateCode("10").build(), null);
    assertEquals(PartyPlace.Source.ADDRESS_STATE, placement.placeVendor("v1").source());
  }

  @Test
  void unknownPartyMakesTheSupplyLocal() {
    vendor(null, null, "Shop 4, Main Market");
    assertEquals(PartyPlace.UNKNOWN, placement.placeVendor("v1"));
    assertFalse(placement.isInterstatePurchase("s1", "v1"));
    assertFalse(placement.isInterstatePurchase("s1", null));
    assertFalse(placement.isInterstatePurchase("s1", "missing"));
  }

  @Test
  void shopWithoutGstinIsPlacedByItsAddress() {
    Shop shop = new Shop();
    shop.setShopId("s2");
    Location loc = new Location();
    loc.setState("Karnataka");
    shop.setLocation(loc);
    when(shops.findById("s2")).thenReturn(Optional.of(shop));
    assertEquals(new PartyPlace("29", PartyPlace.Source.ADDRESS_STATE), placement.placeShop("s2"));
  }
}
