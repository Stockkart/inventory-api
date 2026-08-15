package com.inventory.product.rest.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.inventory.product.domain.model.enums.ShopType;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

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
  private ShopType shopType;
  private String sgst;
  private String cgst;
  private String status;
  private Instant createdAt;
}
