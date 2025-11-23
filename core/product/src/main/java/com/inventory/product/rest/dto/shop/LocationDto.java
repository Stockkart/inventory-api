package com.inventory.product.rest.dto.shop;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class LocationDto {
  private String primaryAddress;
  private String secondaryAddress; // optional
  private String state;
  private String city;
  private String pin;
  private String country; // default "IND" if omitted
}

