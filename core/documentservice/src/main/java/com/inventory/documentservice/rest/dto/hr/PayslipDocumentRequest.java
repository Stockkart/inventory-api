package com.inventory.documentservice.rest.dto.hr;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

/**
 * Neutral payslip payload (HR R10.2): plain strings and amounts only, so documentservice never
 * depends on the HR module. Amounts are left out of {@code toString}, so they never reach a log.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PayslipDocumentRequest {

  private String shopName;
  /** One line; null = no address printed. */
  private String shopAddress;

  private String employeeName;
  private String employeeCode;
  /** null = not printed. */
  private String designation;
  /** e.g. {@code April 2025}. */
  private String periodLabel;

  /** Paid-days breakdown, e.g. ("Present", "24"), ("Paid days", "28 of 30"). */
  @Builder.Default private List<Row> dayRows = new ArrayList<>();

  @ToString.Exclude @Builder.Default private List<Item> earnings = new ArrayList<>();
  @ToString.Exclude @Builder.Default private List<Item> deductions = new ArrayList<>();

  @ToString.Exclude private BigDecimal grossPay;
  @ToString.Exclude private BigDecimal totalDeductions;
  /** Gross − deductions before the rupee rounding. */
  @ToString.Exclude private BigDecimal netBeforeRounding;
  /** net − netBeforeRounding (|rounding| ≤ 0.50); zero = no rounding line printed. */
  @ToString.Exclude private BigDecimal rounding;
  @ToString.Exclude private BigDecimal netPay;
  /** Net pay in words, e.g. "Thirteen Thousands One Hundred and Forty Six Rupees only". */
  @ToString.Exclude private String netPayInWords;
  /** Staff advance still owed after this month; null = no advance. */
  @ToString.Exclude private BigDecimal advanceBalance;

  /** e.g. "Paid on 2025-05-02 (Bank)"; null = not printed. */
  private String paymentNote;
  /** e.g. "Generated 03 May 2025, 10:15". */
  private String generatedAtLabel;

  @Data
  @NoArgsConstructor
  @AllArgsConstructor
  public static class Row {
    private String label;
    private String value;
  }

  @Data
  @NoArgsConstructor
  @AllArgsConstructor
  public static class Item {
    private String label;
    @ToString.Exclude private BigDecimal amount;
  }
}
