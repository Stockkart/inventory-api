package com.inventory.analytics.mis.rest.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MisSalesRowDto {
  private String saleId;
  private LocalDate date;
  private String invoiceNo;
  private String customerId;
  private String customer;
  private String paymentMethod;
  private BigDecimal cash;
  private BigDecimal online;
  private BigDecimal credit;
  private BigDecimal subTotal;
  private BigDecimal tax;
  private BigDecimal discount;
  private BigDecimal grandTotal;
  private BigDecimal cost;
  private BigDecimal profit;
  private BigDecimal margin;
}
