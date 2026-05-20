package com.inventory.product.domain.model;

import com.inventory.product.domain.model.enums.SellUnitRule;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * GST UQC (Unique Quantity Code) with pharmacy packaging / sell behaviour.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PackagingUnitDefinition {
  /** Three-letter GST UQC code (e.g. TBS, MLT, PCS). */
  private String uqc;
  /** GST quantity description (e.g. TABLETS, MILLILITRE). */
  private String label;
  /** MEASURE, WEIGHT, VOLUME, LENGTH, AREA, OTHER */
  private String category;
  private SellUnitRule sellUnitRule;
  /** When {@link #allowsUnitsPerPack}, default pack UQC for unitConversions.unit. */
  private String defaultPackUqc;
  private boolean allowsUnitsPerPack;
  private String registrationHint;
  private String sellHint;
}
