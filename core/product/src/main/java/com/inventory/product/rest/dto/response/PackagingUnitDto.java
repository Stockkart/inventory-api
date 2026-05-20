package com.inventory.product.rest.dto.response;

import com.inventory.product.domain.model.enums.SellUnitRule;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PackagingUnitDto {
  private String uqc;
  private String label;
  private String category;
  private SellUnitRule sellUnitRule;
  private String defaultPackUqc;
  private boolean allowsUnitsPerPack;
  private String registrationHint;
  private String sellHint;
}
