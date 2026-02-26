package com.inventory.taxation.rest.dto;

import com.inventory.taxation.domain.model.GstInvoiceLine;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;
import java.util.stream.Collectors;

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

    long noOfRecipients = lines.stream()
        .map(GstInvoiceLine::getRecipientGstin)
        .filter(g -> g != null && !g.isBlank())
        .distinct()
        .count();
    int noOfInvoices = lines.size();
    BigDecimal totalInvoiceValue = lines.stream()
        .map(GstInvoiceLine::getInvoiceValue)
        .filter(v -> v != null)
        .reduce(BigDecimal.ZERO, BigDecimal::add);
    BigDecimal taxableValue = lines.stream()
        .map(GstInvoiceLine::getTaxableValue)
        .filter(v -> v != null)
        .reduce(BigDecimal.ZERO, BigDecimal::add);
    BigDecimal cessAmount = lines.stream()
        .map(GstInvoiceLine::getCessAmount)
        .filter(v -> v != null)
        .reduce(BigDecimal.ZERO, BigDecimal::add);

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
