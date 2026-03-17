package com.inventory.taxation.rest.dto;

import com.inventory.taxation.domain.gstr2.Gstr2AtadjLine;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class Gstr2AtadjLineDto {
  private String placeOfSupply;
  private BigDecimal rate;
  private BigDecimal grossAdvanceToBeAdjusted;
  private BigDecimal cessAdjusted;

  public static Gstr2AtadjLineDto from(Gstr2AtadjLine line) {
    if (line == null) return null;
    Gstr2AtadjLineDto dto = new Gstr2AtadjLineDto();
    dto.setPlaceOfSupply(line.getPlaceOfSupply());
    dto.setRate(line.getRate());
    dto.setGrossAdvanceToBeAdjusted(line.getGrossAdvanceToBeAdjusted());
    dto.setCessAdjusted(line.getCessAdjusted());
    return dto;
  }
}
