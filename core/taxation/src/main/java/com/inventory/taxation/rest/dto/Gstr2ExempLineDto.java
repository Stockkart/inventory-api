package com.inventory.taxation.rest.dto;

import com.inventory.taxation.domain.gstr2.Gstr2ExempLine;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class Gstr2ExempLineDto {
  private String description;
  private BigDecimal compositionTaxablePerson;
  private BigDecimal nilRatedSupplies;
  private BigDecimal exemptedOtherThanNilOrNonGst;
  private BigDecimal nonGstSupplies;

  public static Gstr2ExempLineDto from(Gstr2ExempLine line) {
    if (line == null) return null;
    Gstr2ExempLineDto dto = new Gstr2ExempLineDto();
    dto.setDescription(line.getDescription());
    dto.setCompositionTaxablePerson(line.getCompositionTaxablePerson());
    dto.setNilRatedSupplies(line.getNilRatedSupplies());
    dto.setExemptedOtherThanNilOrNonGst(line.getExemptedOtherThanNilOrNonGst());
    dto.setNonGstSupplies(line.getNonGstSupplies());
    return dto;
  }
}
