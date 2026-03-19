package com.inventory.taxation.rest.dto;

import com.inventory.taxation.domain.gstr2.Gstr2CdnurLine;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
public class Gstr2CdnurLineDto {
  private String noteNumber;
  private LocalDate noteDate;
  private String invoiceNo;
  private LocalDate invoiceDate;
  private String preGst;
  private String documentType;
  private String reasonForIssuing;
  private String supplyType;
  private String invoiceType;
  private BigDecimal noteValue;
  private BigDecimal rate;
  private BigDecimal taxableValue;
  private BigDecimal integratedTaxPaid;
  private BigDecimal centralTaxPaid;
  private BigDecimal stateUtTaxPaid;
  private BigDecimal cessPaid;
  private String itcEligibility;
  private BigDecimal availedItcIntegrated;

  public static Gstr2CdnurLineDto from(Gstr2CdnurLine line) {
    if (line == null) return null;
    Gstr2CdnurLineDto dto = new Gstr2CdnurLineDto();
    dto.setNoteNumber(line.getNoteNumber());
    dto.setNoteDate(line.getNoteDate());
    dto.setInvoiceNo(line.getInvoiceNo());
    dto.setInvoiceDate(line.getInvoiceDate());
    dto.setPreGst(line.getPreGst());
    dto.setDocumentType(line.getDocumentType());
    dto.setReasonForIssuing(line.getReasonForIssuing());
    dto.setSupplyType(line.getSupplyType());
    dto.setInvoiceType(line.getInvoiceType());
    dto.setNoteValue(line.getNoteValue());
    dto.setRate(line.getRate());
    dto.setTaxableValue(line.getTaxableValue());
    dto.setIntegratedTaxPaid(line.getIntegratedTaxPaid());
    dto.setCentralTaxPaid(line.getCentralTaxPaid());
    dto.setStateUtTaxPaid(line.getStateUtTaxPaid());
    dto.setCessPaid(line.getCessPaid());
    dto.setItcEligibility(line.getItcEligibility());
    dto.setAvailedItcIntegrated(line.getAvailedItcIntegrated());
    return dto;
  }
}
