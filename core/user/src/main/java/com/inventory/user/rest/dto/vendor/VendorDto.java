package com.inventory.user.rest.dto.vendor;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class VendorDto {
  private String vendorId;
  private String name;
  private String contactEmail;
  private String contactPhone;
  private String address;
  private String companyName;
  private String businessType;
  private String gstinUin;
  /** Optional. Set when vendor is linked to a registered user. */
  private String userId;
  private Instant createdAt;
  private Instant updatedAt;
}

