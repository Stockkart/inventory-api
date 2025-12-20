package com.inventory.user.rest.dto.vendor;

import lombok.Data;

@Data
public class CreateVendorRequest {
  private String name;
  private String contactEmail;
  private String contactPhone;
  private String address;
  private String companyName;
  private String businessType;
}

