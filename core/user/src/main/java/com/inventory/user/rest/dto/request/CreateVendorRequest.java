package com.inventory.user.rest.dto.request;

import lombok.Data;

@Data
public class CreateVendorRequest {
  private String name;
  private String contactEmail;
  private String contactPhone;
  private String address;
  private String companyName;
  private String businessType;
  private String gstinUin; // GSTIN or UIN (Unique Identification Number)
  /** Optional. When set, links this vendor to a registered user (enables credit sync across shops). */
  private String userId;
}
