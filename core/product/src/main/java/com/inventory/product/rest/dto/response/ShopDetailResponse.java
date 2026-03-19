package com.inventory.product.rest.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ShopDetailResponse {

  private String shopId;
  private String name;
  private String contactEmail;
  private String contactPhone;
  private String gstinNo;
  /** PAN derived from GSTIN: 10 chars from 3rd character (1-based). */
  private String panNo;
  private String dlNo;
  private String tagline;
  private LocationDto location;

}
