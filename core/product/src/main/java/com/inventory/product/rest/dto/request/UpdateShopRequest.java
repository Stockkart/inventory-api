package com.inventory.product.rest.dto.request;

import com.inventory.product.rest.dto.response.LocationDto;
import lombok.Data;

@Data
public class UpdateShopRequest {

  /** Optional: update shop tagline. */
  private String tagline;

  /** Optional: update shop location. */
  private LocationDto location;
}
