package com.inventory.taxation.gstin;

import com.inventory.common.gst.Gstin;
import com.inventory.common.util.GstStateCode;
import java.time.Instant;
import java.time.LocalDate;

/**
 * What the vendor form gets back for a GSTIN.
 *
 * @param valid the GSTIN passes the offline format and check-character test
 * @param verified the GST network has a record for it (now or earlier)
 * @param verificationAvailable a lookup provider is configured; false means "offline check only"
 * @param problem why it is not valid, in plain words; null when valid
 * @param stateCode from the record when verified, else from the GSTIN itself
 */
public record GstinLookupResponse(
    String gstin,
    boolean valid,
    boolean verified,
    boolean verificationAvailable,
    String problem,
    String status,
    String legalName,
    String tradeName,
    String taxpayerType,
    String stateCode,
    String stateName,
    LocalDate registrationDate,
    LocalDate cancellationDate,
    String address,
    String city,
    String pincode,
    GstinRecord.AddressDetails addressDetails,
    Instant lastCheckedAt) {

  static GstinLookupResponse invalid(String raw, String problem, boolean available) {
    return new GstinLookupResponse(
        Gstin.normalize(raw), false, false, available, problem, null, null, null, null, null, null, null, null, null,
        null, null, null, null);
  }

  /** Valid but nothing on record (provider off, down, or the network does not know it). */
  static GstinLookupResponse unverified(Gstin gstin, boolean available) {
    String code = gstin.stateCode();
    return new GstinLookupResponse(
        gstin.value(), true, false, available, null, null, null, null, null, code, stateName(code), null, null, null,
        null, null, null, null);
  }

  static GstinLookupResponse of(GstinRecord r, boolean available) {
    return new GstinLookupResponse(
        r.getGstin(), true, true, available, null, r.getStatus(), r.getLegalName(), r.getTradeName(), r.getTaxpayerType(),
        r.getStateCode(), stateName(r.getStateCode()), r.getRegistrationDate(), r.getCancellationDate(), r.getAddress(),
        r.getCity(), r.getPincode(), r.getAddressDetails(), r.getLastCheckedAt());
  }

  private static String stateName(String code) {
    String formatted = GstStateCode.format(code);
    int dash = formatted.indexOf('-');
    return dash > 0 ? formatted.substring(dash + 1) : formatted;
  }
}
