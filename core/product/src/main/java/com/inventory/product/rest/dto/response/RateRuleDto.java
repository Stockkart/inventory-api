package com.inventory.product.rest.dto.response;

import com.inventory.pricing.rest.dto.response.SchemeDto;
import java.math.BigDecimal;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** One rate rule as the settings screen sends and reads it; {@code base} is the wire name. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RateRuleDto {
  private String name;
  /** sellingPrice, priceToRetail or maximumRetailPrice. */
  private String base;
  private BigDecimal discountPercent;
  private SchemeDto scheme;
}
