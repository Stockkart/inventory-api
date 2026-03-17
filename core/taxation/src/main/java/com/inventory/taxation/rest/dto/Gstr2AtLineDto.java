package com.inventory.taxation.rest.dto;

import com.inventory.taxation.domain.gstr2.Gstr2AtLine;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class Gstr2AtLineDto {
  private String placeOfSupply;
  private BigDecimal rate;
  private BigDecimal grossAdvancePaid;
  private BigDecimal cessAmount;

  public static Gstr2AtLineDto from(Gstr2AtLine line) {
    if (line == null) return null;
    Gstr2AtLineDto dto = new Gstr2AtLineDto();
    dto.setPlaceOfSupply(line.getPlaceOfSupply());
    dto.setRate(line.getRate());
    dto.setGrossAdvancePaid(line.getGrossAdvancePaid());
    dto.setCessAmount(line.getCessAmount());
    return dto;
  }
}
