package com.inventory.hr.rest.dto.response;

import com.inventory.hr.domain.model.enums.JoinOutcome;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * The answer to an approve or reject (worker-app R4.5, R4.7): the request, how it was closed and,
 * on approval, the employee created or linked.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class JoinRequestDecision {
  private String id;
  private JoinOutcome outcome;
  /** Set on approval; null on rejection. */
  private String employeeId;
}
