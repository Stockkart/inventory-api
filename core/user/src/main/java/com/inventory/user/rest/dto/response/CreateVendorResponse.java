package com.inventory.user.rest.dto.response;

import com.inventory.common.gst.PostalAddress;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.time.LocalDate;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CreateVendorResponse {
  private String vendorId;
  private String name;
  private String contactEmail;
  private String contactPhone;
  private String address;
  private PostalAddress postalAddress;
  private String companyName;
  private String businessType;
  private String gstinUin;
  private Instant gstinVerifiedAt;
  private String gstinStatus;
  private String dlNo;
  private LocalDate dlExpiry;
  /** True when the D.L. expired before today (India); null when there is no expiry date. */
  private Boolean dlExpired;
  private Instant createdAt;
  private Instant updatedAt;
}
