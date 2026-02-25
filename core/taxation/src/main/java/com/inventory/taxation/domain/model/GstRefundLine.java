package com.inventory.taxation.domain.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Credit/Debit note line for GSTR-1 (CDNR = registered, CDNUR = unregistered).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GstRefundLine {

  private boolean registered; // true = CDNR, false = CDNUR
  private String recipientGstin;
  private String receiverName;
  private String noteNumber;
  private LocalDate noteDate;
  private String noteType;       // C = Credit, D = Debit
  private String placeOfSupply;
  private String reverseCharge;
  private String noteSupplyType;
  private BigDecimal noteValue;
  private String applicableTaxPct;
  private BigDecimal rate;
  private BigDecimal taxableValue;
  private BigDecimal cessAmount;
  private String urType;         // For CDNUR
}
