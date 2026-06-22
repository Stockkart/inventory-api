package com.inventory.pluginengine.pricing;

import java.math.BigDecimal;

public record PricingSchemeEntry(
    String schemeType, Integer schemePayFor, Integer schemeFree, BigDecimal schemePercentage) {

  public boolean isEmpty() {
    return schemeType == null
        && schemePayFor == null
        && schemeFree == null
        && schemePercentage == null;
  }
}
