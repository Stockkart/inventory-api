package com.inventory.user.rest.dto.vendor;

import lombok.Data;

@Data
public class SearchVendorRequest {
  private String phone;
  private String email;
}

