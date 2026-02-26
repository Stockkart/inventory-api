package com.inventory.taxation.rest.dto;

import com.inventory.taxation.domain.model.GstInvoiceLine;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Slim DTO for b2cs tab - only fields that appear in the GSTR-1 sheet.
 * Type | Place of Supply | Applicable %Tax | Rate | Taxable Value | Cess Amount | E-Commerce GSTIN
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class GstB2csLineDto {

  private String type;
  private String placeOfSupply;
  private String applicableTaxPct;
  private BigDecimal rate;
  private BigDecimal taxableValue;
  private BigDecimal cessAmount;
  private String ecommerceGstin;

  public static GstB2csLineDto from(GstInvoiceLine line) {
    if (line == null) return null;
    GstB2csLineDto dto = new GstB2csLineDto();
    dto.setType(line.getB2csType() != null ? line.getB2csType() : "OE");
    dto.setPlaceOfSupply(line.getPlaceOfSupply());
    dto.setApplicableTaxPct(line.getApplicableTaxPct());
    dto.setRate(line.getRate());
    dto.setTaxableValue(line.getTaxableValue());
    dto.setCessAmount(line.getCessAmount());
    dto.setEcommerceGstin(line.getEcommerceGstin() != null ? line.getEcommerceGstin() : "");
    return dto;
  }

  public static List<GstB2csLineDto> fromList(List<GstInvoiceLine> lines) {
    if (lines == null) return List.of();
    return lines.stream().map(GstB2csLineDto::from).collect(Collectors.toList());
  }
}
