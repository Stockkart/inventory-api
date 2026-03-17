package com.inventory.taxation.rest.dto;

import com.inventory.taxation.domain.gstr2.Gstr2ItcrLine;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class Gstr2ItcrLineDto {
  private String description;
  private String toBeAddedOrReduced;
  private BigDecimal itcIntegratedTaxAmount;
  private BigDecimal itcCentralTaxAmount;
  private BigDecimal itcStateUtTaxAmount;
  private BigDecimal itcCessAmount;

  public static Gstr2ItcrLineDto from(Gstr2ItcrLine line) {
    if (line == null) return null;
    Gstr2ItcrLineDto dto = new Gstr2ItcrLineDto();
    dto.setDescription(line.getDescription());
    dto.setToBeAddedOrReduced(line.getToBeAddedOrReduced());
    dto.setItcIntegratedTaxAmount(line.getItcIntegratedTaxAmount());
    dto.setItcCentralTaxAmount(line.getItcCentralTaxAmount());
    dto.setItcStateUtTaxAmount(line.getItcStateUtTaxAmount());
    dto.setItcCessAmount(line.getItcCessAmount());
    return dto;
  }
}
