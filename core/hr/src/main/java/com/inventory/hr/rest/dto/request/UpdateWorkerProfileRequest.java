package com.inventory.hr.rest.dto.request;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

/**
 * Body of {@code PUT /api/v1/hr/worker/profile} (worker-app R8.2): the worker's own name and phone
 * under the sign-up rules (R1.2). The e-mail cannot be changed from the app. Mirrored in {@code
 * attendance-protocol/src/worker.types.ts} as {@code UpdateWorkerProfileRequest}.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class UpdateWorkerProfileRequest {
  private String name;
  @ToString.Exclude private String phone;
}
