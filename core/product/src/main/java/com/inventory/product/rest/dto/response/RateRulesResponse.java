package com.inventory.product.rest.dto.response;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RateRulesResponse {
  /** False for a vertical that does not support rate rules (cafe). */
  private boolean enabled;
  /** Bases this shop type may use. */
  private List<String> baseOptions;
  private List<RateRuleDto> rules;
  /** Whether the calling user holds the rate-rules edit grant. */
  private boolean canEdit;
}
