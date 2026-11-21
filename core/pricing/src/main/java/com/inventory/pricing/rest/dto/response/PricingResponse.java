package com.inventory.pricing.rest.dto.response;

import com.inventory.pricing.domain.model.SkippedRateRule;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PricingResponse {
  private String id;
  private String shopId;
  private BigDecimal maximumRetailPrice;
  private BigDecimal costPrice;
  /** Landed cost per unit after purchase scheme and additional discount. */
  private BigDecimal effectiveCostPrice;
  private Boolean costPriceIncludesTax;
  private BigDecimal priceToRetail;
  /** Each rate with its terms and read-only {@code termsText} (null for a legacy rate). */
  private List<RateDto> rates;
  private String defaultRate;
  private BigDecimal sellingPrice;
  private BigDecimal saleAdditionalDiscount;
  private BigDecimal purchaseAdditionalDiscount;
  private SchemeDto purchaseScheme;
  private SchemeDto saleScheme;
  private String sgst;
  private String cgst;
  private Instant createdAt;
  private Instant updatedAt;
  /**
   * Rates this write left below cost or did not generate: a kept override priced below cost, or a
   * rule skipped below cost ({@code [{name, reason}]}, reason {@code BELOW_COST}). Set by PATCH,
   * bulk-update and inventory update on a shop with rate rules; null or empty when there are none.
   */
  private List<SkippedRateRule> warnings;

  /**
   * The names this batch's {@code defaultRate} may take, in display order: the built-in options
   * ({@code priceToRetail}, {@code maximumRetailPrice}, {@code costPrice}) then every rate not
   * built on the selling price (a default on the selling price falls back to priceToRetail).
   */
  private List<String> defaultRateOptions;
}
