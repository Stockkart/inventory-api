package com.inventory.common.gst;

import java.time.Instant;
import java.time.LocalDate;

/**
 * What the GST network says about a GSTIN — the part of the record the rest of the application
 * reads. Provider-neutral: whichever service answered, this is the shape callers see.
 *
 * @param status {@code Active}, {@code Cancelled}, {@code Suspended}, … as the network states it
 * @param stateCode the two-digit state code the registration belongs to
 * @param lastCheckedAt when the network was last asked
 */
public record GstinRegistration(
    String gstin,
    String legalName,
    String tradeName,
    String status,
    String taxpayerType,
    String stateCode,
    LocalDate registrationDate,
    LocalDate cancellationDate,
    String address,
    String city,
    String pincode,
    Instant lastCheckedAt) {

  public boolean isActive() {
    return "ACTIVE".equalsIgnoreCase(status);
  }

  /** Composition dealers may not charge GST on their bills. */
  public boolean isComposition() {
    return taxpayerType != null && taxpayerType.toLowerCase().contains("composition");
  }
}
