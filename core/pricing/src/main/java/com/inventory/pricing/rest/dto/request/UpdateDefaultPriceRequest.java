package com.inventory.pricing.rest.dto.request;

import com.inventory.pricing.domain.model.Rate;
import lombok.Data;

import java.util.List;

@Data
public class UpdateDefaultPriceRequest {
  private List<Rate> rates;
  private String defaultRate;
  /** Rate names to regenerate from their rule, clearing a per-batch override. Needs the grant. */
  private List<String> resetRates;
}
