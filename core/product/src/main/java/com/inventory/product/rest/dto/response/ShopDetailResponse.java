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
  /** PAN from stored shop PAN, or derived from GSTIN (chars 3–12). */
  private String panNo;
  private String dlNo;
  private String fssai;
  private String tagline;
  private LocationDto location;
  private String verticalId;
  private String pluginVersion;

}
