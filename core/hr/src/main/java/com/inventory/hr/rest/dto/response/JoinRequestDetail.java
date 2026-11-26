package com.inventory.hr.rest.dto.response;

import com.inventory.hr.domain.model.enums.EmployeeStatus;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * A {@link JoinRequestRow} plus the possible match (worker-app R4.3): the shop employee whose e-mail
 * equals the snapshot's (case-insensitive) or whose phone equals it after normalisation, ACTIVE or
 * EXITED. {@code possibleMatch} is null when none.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class JoinRequestDetail extends JoinRequestRow {

  private PossibleMatch possibleMatch;

  /** The employee who may be the same person; {@code linked} = already has a login. */
  @Data
  @NoArgsConstructor
  @AllArgsConstructor
  public static class PossibleMatch {
    private String employeeId;
    private String code;
    private String name;
    private EmployeeStatus status;
    private boolean linked;
  }
}
