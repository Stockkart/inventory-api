package com.inventory.product.domain.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class Location {
  private String primaryAddress;
  private String secondaryAddress;
  private String state;
  private String city;
  private String pin;
  private String country;
}

