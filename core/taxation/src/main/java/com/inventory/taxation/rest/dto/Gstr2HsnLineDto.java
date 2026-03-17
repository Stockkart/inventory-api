package com.inventory.taxation.rest.dto;

import com.inventory.taxation.domain.model.GstHsnLine;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class Gstr2HsnLineDto {
  private String hsn;
  private String description;
  private String uqc;
  private BigDecimal totalQuantity;
  private BigDecimal totalValue;
  private BigDecimal rate;
  private BigDecimal taxableValue;
  private BigDecimal integratedTaxAmount;
  private BigDecimal centralTaxAmount;
  private BigDecimal stateUtTaxAmount;
  private BigDecimal cessAmount;

  public static Gstr2HsnLineDto from(GstHsnLine line) {
    if (line == null) return null;
    Gstr2HsnLineDto dto = new Gstr2HsnLineDto();
    dto.setHsn(line.getHsn());
    dto.setDescription(line.getDescription());
    dto.setUqc(line.getUqc());
    dto.setTotalQuantity(line.getTotalQuantity());
    dto.setTotalValue(line.getTotalValue());
    dto.setRate(line.getRate());
    dto.setTaxableValue(line.getTaxableValue());
    dto.setIntegratedTaxAmount(line.getIntegratedTaxAmount());
    dto.setCentralTaxAmount(line.getCentralTaxAmount());
    dto.setStateUtTaxAmount(line.getStateUtTaxAmount());
    dto.setCessAmount(line.getCessAmount());
    return dto;
  }
}
