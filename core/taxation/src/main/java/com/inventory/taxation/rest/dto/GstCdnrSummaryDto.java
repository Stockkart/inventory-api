package com.inventory.taxation.rest.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/** Summary For CDNR(9B): No. of Recipients, No. of Notes, Total Note Value, Total Taxable Value, Total Cess */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class GstCdnrSummaryDto {
  private int noOfRecipients;
  private int noOfNotes;
  private BigDecimal totalNoteValue;
  private BigDecimal totalTaxableValue;
  private BigDecimal totalCess;
}
