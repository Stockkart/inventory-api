package com.inventory.taxation.rest.dto;

import com.inventory.taxation.domain.gstr2.Gstr2B2bLine;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
public class Gstr2B2bLineDto {
  private String supplierGstin;
  private String invoiceNo;
  private LocalDate invoiceDate;
  private BigDecimal invoiceValue;
  private String placeOfSupply;
  private String reverseCharge;
  private String invoiceType;
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

  public static Gstr2B2bLineDto from(Gstr2B2bLine line) {
    if (line == null) return null;
    Gstr2B2bLineDto dto = new Gstr2B2bLineDto();
    dto.setSupplierGstin(line.getSupplierGstin());
    dto.setInvoiceNo(line.getInvoiceNo());
    dto.setInvoiceDate(line.getInvoiceDate());
    dto.setInvoiceValue(line.getInvoiceValue());
    dto.setPlaceOfSupply(line.getPlaceOfSupply());
    dto.setReverseCharge(line.getReverseCharge());
    dto.setInvoiceType(line.getInvoiceType());
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
