package com.inventory.taxation.domain.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * Single line for GSTR-1 outward supply (B2B, B2CL, B2CS, EXP).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GstInvoiceLine {

  private SupplyType supplyType;
  private String recipientGstin;
  private String receiverName;
  private String invoiceNo;
  private LocalDate invoiceDate;
  private BigDecimal invoiceValue;
  private String placeOfSupply;   // State code or name
  private String reverseCharge;   // Y/N
  private String applicableTaxPct;
  private String invoiceType;
  private String ecommerceGstin;
  private BigDecimal rate;
  private BigDecimal taxableValue;
  private BigDecimal cessAmount;
  private BigDecimal integratedTaxAmount;
  private BigDecimal centralTaxAmount;
  private BigDecimal stateTaxAmount;
  /** For B2CS: type e.g. "OE" (out of state) */
  private String b2csType;
  /** Export: port code, shipping bill, etc. */
  private String exportType;
  private String portCode;
  private String shippingBillNo;
  private LocalDate shippingBillDate;
  private BigDecimal cessValue;
}
