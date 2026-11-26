package com.inventory.hr.rest.dto.request;

import lombok.Data;

/**
 * {@code POST /api/v1/hr/attendance-app/consent}: the worker tapped "I agree" on a newer consent
 * version (R9.13). Field names match {@code ConsentRequest} in {@code attendance-protocol}.
 */
@Data
public class ConsentRequest {
  /** The published version the worker agreed to; must exist (400 otherwise). */
  private Integer version;
}
