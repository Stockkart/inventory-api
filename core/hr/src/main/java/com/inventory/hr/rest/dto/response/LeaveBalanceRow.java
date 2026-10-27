package com.inventory.hr.rest.dto.response;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** One employee's balances for a leave year (R6.3, R12.4). */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class LeaveBalanceRow {
  private String employeeId;
  private String code;
  private String name;
  private String leaveYear;
  private List<Balance> balances = new ArrayList<>();

  @Data
  @NoArgsConstructor
  @AllArgsConstructor
  public static class Balance {
    private String leaveTypeId;
    private String leaveTypeName;
    private boolean paid;
    /** Prorated in the first leave year (R6.4); null = no limit. */
    private BigDecimal quota;
    /** Approved days of this type in the year. */
    private BigDecimal used;
    /** quota − used; null = no limit. */
    private BigDecimal available;
  }
}
