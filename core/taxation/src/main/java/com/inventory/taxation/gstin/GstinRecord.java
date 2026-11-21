package com.inventory.taxation.gstin;

import com.inventory.common.gst.GstinRegistration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * One GSTIN as the GST network describes it, kept so the network is asked once per GSTIN,
 * system-wide. Shared by every shop: a supplier's registration is the same fact for all of them.
 *
 * <p>{@code raw} is the provider's answer as received, so a field not mapped today can be read
 * later without another paid call, and a change of provider can be re-mapped from what is on disk.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "gstin_registry")
public class GstinRecord {

  /** The GSTIN itself, uppercased. */
  @Id private String gstin;

  private String legalName;
  private String tradeName;
  /** As the network states it: Active, Cancelled, Suspended, … */
  private String status;
  /** Regular, Composition, SEZ, … */
  private String taxpayerType;
  private LocalDate registrationDate;
  private LocalDate cancellationDate;
  /** Two-digit state code of the registration. */
  private String stateCode;

  /** Principal place of business, as one line. */
  private String address;
  private String city;
  private String pincode;
  /** The same address as fields, as the network breaks it down. */
  private AddressDetails addressDetails;

  /** Which service answered, e.g. {@code gstinapi.in}. */
  private String provider;
  /** First time the network was asked about this GSTIN. */
  private Instant fetchedAt;
  /** Last time it was asked (moves on every re-verify). */
  private Instant lastCheckedAt;
  /** The provider's answer, untouched. */
  private Map<String, Object> raw;

  @Data
  @Builder
  @NoArgsConstructor
  @AllArgsConstructor
  public static class AddressDetails {
    private String buildingNumber;
    private String buildingName;
    private String floor;
    private String street;
    private String locality;
    private String district;
    private String city;
    private String state;
    private String landmark;
    private String pincode;
  }

  public GstinRegistration toRegistration() {
    return new GstinRegistration(
        gstin, legalName, tradeName, status, taxpayerType, stateCode, registrationDate, cancellationDate,
        address, city, pincode, lastCheckedAt);
  }
}
