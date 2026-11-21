package com.inventory.product.rest.dto.request;

import com.inventory.product.rest.dto.response.RateRuleDto;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Replaces the shop's ordered rate rule list; an empty or missing list clears it. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class UpdateRateRulesRequest {
  private List<RateRuleDto> rules;
}
