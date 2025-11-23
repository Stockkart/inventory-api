package com.inventory.product.rest.dto.shop;

import lombok.Data;

@Data
public class RegisterShopRequest {

  private String name;
  private String businessId;
  private LocationDto location;
  private String contactEmail;
  private String contactPhone;
}

