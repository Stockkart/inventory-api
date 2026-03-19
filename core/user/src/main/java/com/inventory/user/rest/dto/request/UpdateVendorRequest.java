package com.inventory.user.rest.dto.request;

import lombok.Data;

@Data
public class UpdateVendorRequest {
  private String name;
  private String contactEmail;
  private String contactPhone;
  private String address;
  private String companyName;
  private String businessType;
  private String gstinUin;
}
