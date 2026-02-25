package com.inventory.taxation.rest.dto;

import com.inventory.taxation.domain.model.GstInvoiceLine;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Slim DTO for b2b,sez,de tab - only fields that appear in the GSTR-1 sheet.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class GstB2bSezDeLineDto {

  private String recipientGstin;
  private String receiverName;
  private String invoiceNo;
  private LocalDate invoiceDate;
  private BigDecimal invoiceValue;
  private String placeOfSupply;
  private String reverseCharge;
  private String applicableTaxPct;
  private String invoiceType;
  private String ecommerceGstin;
  private BigDecimal rate;
  private BigDecimal taxableValue;
  private BigDecimal cessAmount;

  public static GstB2bSezDeLineDto from(GstInvoiceLine line) {
    if (line == null) return null;
    GstB2bSezDeLineDto dto = new GstB2bSezDeLineDto();
    dto.setRecipientGstin(line.getRecipientGstin());
    dto.setReceiverName(line.getReceiverName());
    dto.setInvoiceNo(line.getInvoiceNo());
    dto.setInvoiceDate(line.getInvoiceDate());
    dto.setInvoiceValue(line.getInvoiceValue());
    dto.setPlaceOfSupply(line.getPlaceOfSupply());
    dto.setReverseCharge(line.getReverseCharge());
    dto.setApplicableTaxPct(line.getApplicableTaxPct());
    dto.setInvoiceType(line.getInvoiceType());
    dto.setEcommerceGstin(line.getEcommerceGstin());
    dto.setRate(line.getRate());
    dto.setTaxableValue(line.getTaxableValue());
    dto.setCessAmount(line.getCessAmount());
    return dto;
  }

  public static List<GstB2bSezDeLineDto> fromList(List<GstInvoiceLine> lines) {
    if (lines == null) return List.of();
    return lines.stream().map(GstB2bSezDeLineDto::from).collect(Collectors.toList());
  }
}
