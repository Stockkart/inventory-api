package com.inventory.product.rest.dto.response;

import com.inventory.pricing.rest.dto.response.SchemeDto;
import java.math.BigDecimal;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RateRulesPreviewResponse {

  private List<PreviewRate> rates;
  private List<SkippedRateRuleDto> skipped;

  @Data
  @Builder
  @NoArgsConstructor
  @AllArgsConstructor
  public static class PreviewRate {
    private String name;
    private String base;
    private BigDecimal baseValue;
    /** Base after the discount, before the scheme, 2 dp. */
    private BigDecimal afterDiscount;
    /** The per-unit figure that would be stored. */
    private BigDecimal price;
    private BigDecimal discountPercent;
    private SchemeDto scheme;
    private String gstMode;
    private String termsText;
  }

  @Data
  @Builder
  @NoArgsConstructor
  @AllArgsConstructor
  public static class SkippedRateRuleDto {
    private String name;
    private String reason;
  }
}
