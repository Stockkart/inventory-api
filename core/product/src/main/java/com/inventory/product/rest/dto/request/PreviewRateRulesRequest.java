package com.inventory.product.rest.dto.request;

import com.inventory.pricing.api.SchemeTerms;
import com.inventory.product.rest.dto.response.RateRuleDto;
import java.math.BigDecimal;
import java.util.List;
import lombok.Data;

/**
 * Base values and the raw cost inputs of a sample batch. {@code rules} is optional: when absent the
 * saved rules are previewed. Cost is resolved on the server exactly as on save.
 */
@Data
public class PreviewRateRulesRequest {
  private BigDecimal sellingPrice;
  private BigDecimal priceToRetail;
  private BigDecimal maximumRetailPrice;
  private BigDecimal costPrice;
  private Boolean costPriceIncludesTax;
  private BigDecimal purchaseAdditionalDiscount;
  private SchemeTerms purchaseScheme;
  private String sgst;
  private String cgst;
  private List<RateRuleDto> rules;
}
