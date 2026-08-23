package com.inventory.taxation.rest.dto;

import com.inventory.taxation.domain.model.GstInvoiceLine;
import com.inventory.taxation.utils.helper.GstTotals;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/**
 * B2B/SEZ/DE tab with summary and line items.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class GstB2bSezDeTabDto {

  private GstB2bSummaryDto summary;
  private List<GstB2bSezDeLineDto> lines;

  public static GstB2bSezDeTabDto fromLines(List<GstInvoiceLine> lines) {
    if (lines == null || lines.isEmpty()) {
      return new GstB2bSezDeTabDto(
          new GstB2bSummaryDto(0, 0, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO),
          List.of());
    }

    long noOfRecipients = GstTotals.countDistinct(lines, GstInvoiceLine::getRecipientGstin);
    int noOfInvoices = GstTotals.countDistinct(lines, GstInvoiceLine::getInvoiceNo);
    BigDecimal totalInvoiceValue = GstTotals.sum(lines, GstInvoiceLine::getInvoiceValue);
    BigDecimal taxableValue = GstTotals.sum(lines, GstInvoiceLine::getTaxableValue);
    BigDecimal cessAmount = GstTotals.sum(lines, GstInvoiceLine::getCessAmount);

    GstB2bSummaryDto summary = new GstB2bSummaryDto(
        (int) noOfRecipients,
        noOfInvoices,
        totalInvoiceValue,
        taxableValue,
        cessAmount);

    List<GstB2bSezDeLineDto> lineDtos = GstB2bSezDeLineDto.fromList(lines);
    return new GstB2bSezDeTabDto(summary, lineDtos);
  }
}
