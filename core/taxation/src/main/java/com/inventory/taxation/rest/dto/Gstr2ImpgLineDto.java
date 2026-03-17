package com.inventory.taxation.rest.dto;

import com.inventory.taxation.domain.gstr2.Gstr2ImpgLine;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
public class Gstr2ImpgLineDto {
  private String portCode;
  private String billOfEntryNo;
  private LocalDate billOfEntryDate;
  private BigDecimal billOfEntryValue;
  private String documentType;
  private String sezSupplierGstin;
  private BigDecimal rate;
  private BigDecimal taxableValue;
  private BigDecimal integratedTaxPaid;
  private BigDecimal cessPaid;
  private String itcEligibility;
  private BigDecimal availedItcIntegrated;
  private BigDecimal availedItcCess;

  public static Gstr2ImpgLineDto from(Gstr2ImpgLine line) {
    if (line == null) return null;
    Gstr2ImpgLineDto dto = new Gstr2ImpgLineDto();
    dto.setPortCode(line.getPortCode());
    dto.setBillOfEntryNo(line.getBillOfEntryNo());
    dto.setBillOfEntryDate(line.getBillOfEntryDate());
    dto.setBillOfEntryValue(line.getBillOfEntryValue());
    dto.setDocumentType(line.getDocumentType());
    dto.setSezSupplierGstin(line.getSezSupplierGstin());
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
