package com.inventory.taxation.rest.dto;

import com.inventory.taxation.domain.gstr2.Gstr2ImpsLine;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
public class Gstr2ImpsLineDto {
  private String invoiceNo;
  private LocalDate invoiceDate;
  private BigDecimal invoiceValue;
  private String placeOfSupply;
  private BigDecimal rate;
  private BigDecimal taxableValue;
  private BigDecimal integratedTaxPaid;
  private BigDecimal cessPaid;
  private String itcEligibility;
  private BigDecimal availedItcIntegrated;
  private BigDecimal availedItcCess;

  public static Gstr2ImpsLineDto from(Gstr2ImpsLine line) {
    if (line == null) return null;
    Gstr2ImpsLineDto dto = new Gstr2ImpsLineDto();
    dto.setInvoiceNo(line.getInvoiceNo());
    dto.setInvoiceDate(line.getInvoiceDate());
    dto.setInvoiceValue(line.getInvoiceValue());
    dto.setPlaceOfSupply(line.getPlaceOfSupply());
    dto.setRate(line.getRate());
    dto.setTaxableValue(line.getTaxableValue());
    dto.setIntegratedTaxPaid(line.getIntegratedTaxPaid());
    dto.setCessPaid(line.getCessPaid());
    dto.setItcEligibility(line.getItcEligibility());
    dto.setAvailedItcIntegrated(line.getAvailedItcIntegrated());
    dto.setAvailedItcCess(line.getAvailedItcCess());
    return dto;
  }
}
