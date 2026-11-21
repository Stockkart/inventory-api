package com.inventory.documentservice.api;

import java.math.BigDecimal;
import java.util.List;
import lombok.Builder;

/**
 * A payslip as plain strings and amounts (HR R10.2), so documentservice never depends on the HR
 * module. Rendered by {@link PayslipDocuments}.
 *
 * <p>Amounts are left out of {@link #toString()}, so they never reach a log.
 *
 * @param shopName shop name
 * @param shopAddress one line; null means no address printed
 * @param employeeName employee name
 * @param employeeCode employee code
 * @param designation null means not printed
 * @param periodLabel e.g. {@code April 2025}
 * @param dayRows paid-days breakdown, e.g. ("Present", "24"), ("Paid days", "28 of 30")
 * @param earnings earning lines
 * @param deductions deduction lines
 * @param grossPay gross pay
 * @param totalDeductions total deductions
 * @param netBeforeRounding gross minus deductions before the rupee rounding
 * @param rounding net minus netBeforeRounding (at most 0.50 either way); zero means no line
 * @param netPay net pay
 * @param netPayInWords e.g. "Thirteen Thousands One Hundred and Forty Six Rupees only"
 * @param advanceBalance staff advance still owed after this month; null means no advance
 * @param paymentNote e.g. "Paid on 2025-05-02 (Bank)"; null means not printed
 * @param generatedAtLabel e.g. "Generated 03 May 2025, 10:15"
 */
@Builder
public record PayslipDocument(
    String shopName,
    String shopAddress,
    String employeeName,
    String employeeCode,
    String designation,
    String periodLabel,
    List<Row> dayRows,
    List<Item> earnings,
    List<Item> deductions,
    BigDecimal grossPay,
    BigDecimal totalDeductions,
    BigDecimal netBeforeRounding,
    BigDecimal rounding,
    BigDecimal netPay,
    String netPayInWords,
    BigDecimal advanceBalance,
    String paymentNote,
    String generatedAtLabel) {

  /** Same fields the old payslip class printed: no amounts, no earning or deduction lines. */
  @Override
  public String toString() {
    return "PayslipDocument(shopName=" + shopName
        + ", shopAddress=" + shopAddress
        + ", employeeName=" + employeeName
        + ", employeeCode=" + employeeCode
        + ", designation=" + designation
        + ", periodLabel=" + periodLabel
        + ", dayRows=" + dayRows
        + ", paymentNote=" + paymentNote
        + ", generatedAtLabel=" + generatedAtLabel + ")";
  }

  /** One line of the paid-days box, e.g. ("Present", "24"). */
  public record Row(String label, String value) {}

  /** One earning or deduction line. The amount is left out of {@link #toString()}. */
  public record Item(String label, BigDecimal amount) {

    @Override
    public String toString() {
      return "PayslipDocument.Item(label=" + label + ")";
    }
  }
}
