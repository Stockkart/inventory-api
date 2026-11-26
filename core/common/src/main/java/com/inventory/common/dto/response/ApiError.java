package com.inventory.common.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ApiError {
  private String message;
  private int status;
  private Map<String, String[]> errors;
  /** Stable machine-readable error name, set for errors the client branches on. */
  private String code;
  /** Extra context for {@link #code}, e.g. the missing feature and the plan that unlocks it. */
  private Map<String, Object> details;

  public ApiError(String message, int status, Map<String, String[]> errors) {
    this(message, status, errors, null, null);
  }
}

