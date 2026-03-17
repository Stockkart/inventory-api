package com.inventory.taxation.rest.dto;

import com.inventory.taxation.domain.gstr2.Gstr2B2burLine;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
public class Gstr2B2burLineDto {
  private String supplierName;
  private String invoiceNo;
  private LocalDate invoiceDate;
  private BigDecimal invoiceValue;
  private String placeOfSupply;
  private String supplyType;
  private BigDecimal rate;
  private BigDecimal taxableValue;
  private BigDecimal integratedTaxPaid;
  private BigDecimal centralTaxPaid;
  private BigDecimal stateUtTaxPaid;
  private BigDecimal cessAmount;
  private String itcEligibility;
  private BigDecimal availedItcIntegrated;
  private BigDecimal availedItcCentral;
  private BigDecimal availedItcStateUt;
  private BigDecimal availedItcCess;

  public static Gstr2B2burLineDto from(Gstr2B2burLine line) {
    if (line == null) return null;
    Gstr2B2burLineDto dto = new Gstr2B2burLineDto();
    dto.setSupplierName(line.getSupplierName());
    dto.setInvoiceNo(line.getInvoiceNo());
    dto.setInvoiceDate(line.getInvoiceDate());
    dto.setInvoiceValue(line.getInvoiceValue());
    dto.setPlaceOfSupply(line.getPlaceOfSupply());
    dto.setSupplyType(line.getSupplyType());
    dto.setRate(line.getRate());
    dto.setTaxableValue(line.getTaxableValue());
    dto.setIntegratedTaxPaid(line.getIntegratedTaxPaid());
    dto.setCentralTaxPaid(line.getCentralTaxPaid());
    dto.setStateUtTaxPaid(line.getStateUtTaxPaid());
    dto.setCessAmount(line.getCessAmount());
    dto.setItcEligibility(line.getItcEligibility());
    dto.setAvailedItcIntegrated(line.getAvailedItcIntegrated());
    dto.setAvailedItcCentral(line.getAvailedItcCentral());
    dto.setAvailedItcStateUt(line.getAvailedItcStateUt());
    dto.setAvailedItcCess(line.getAvailedItcCess());
    return dto;
  }
}
