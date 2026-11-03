package com.inventory.taxation.gstin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.inventory.common.gst.Gstin;
import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The provider's documented response, mapped into our record. */
class GstinApiInLookupProviderTest {

  static final String SAMPLE = """
      {
        "gstin": "27AAPFU0939F1ZV",
        "legal_name": "EXAMPLE PRIVATE LIMITED",
        "trade_name": "EXAMPLE PVT LTD",
        "status": "Active",
        "taxpayer_type": "Regular",
        "business_constitution": null,
        "registration_date": "2017-07-01",
        "cancellation_date": null,
        "state_code": "27",
        "state_jurisdiction": null,
        "address": "SHOP NO. 12, 1ST FLOOR, 123 BUSINESS PARK, RAIPUR",
        "city": "RAIPUR",
        "address_details": {
          "building_number": "SHOP NO. 12", "building_name": "123 BUSINESS PARK", "floor": "1ST FLOOR",
          "street": null, "locality": "RAIPUR", "district": null, "city": null, "state": null,
          "landmark": null, "pincode": "492001"
        },
        "pincode": "492001",
        "nature_of_business": null,
        "block_status": "Unblocked"
      }
      """;

  @Test
  @SuppressWarnings("unchecked")
  void mapsTheDocumentedShape() throws Exception {
    Map<String, Object> data = new ObjectMapper().readValue(SAMPLE, Map.class);
    GstinRecord r = GstinApiInLookupProvider.toRecord(Gstin.parse("27AAPFU0939F1ZV").orElseThrow(), data);

    assertEquals("27AAPFU0939F1ZV", r.getGstin());
    assertEquals("EXAMPLE PRIVATE LIMITED", r.getLegalName());
    assertEquals("EXAMPLE PVT LTD", r.getTradeName());
    assertEquals("Active", r.getStatus());
    assertEquals("Regular", r.getTaxpayerType());
    assertEquals(LocalDate.of(2017, 7, 1), r.getRegistrationDate());
    assertNull(r.getCancellationDate());
    assertEquals("27", r.getStateCode());
    assertEquals("RAIPUR", r.getCity());
    assertEquals("492001", r.getPincode());
    assertEquals("SHOP NO. 12", r.getAddressDetails().getBuildingNumber());
    assertEquals("492001", r.getAddressDetails().getPincode());
    assertNull(r.getAddressDetails().getState());
    assertEquals("gstinapi.in", r.getProvider());
    assertTrue(r.getRaw().containsKey("block_status"), "the whole answer is kept");
    assertTrue(r.toRegistration().isActive());
  }

  @Test
  @SuppressWarnings("unchecked")
  void missingStateCodeFallsBackToTheGstin() throws Exception {
    Map<String, Object> data = new ObjectMapper().readValue(SAMPLE.replace("\"state_code\": \"27\"", "\"state_code\": null"), Map.class);
    GstinRecord r = GstinApiInLookupProvider.toRecord(Gstin.parse("27AAPFU0939F1ZV").orElseThrow(), data);
    assertEquals("27", r.getStateCode());
  }
}
