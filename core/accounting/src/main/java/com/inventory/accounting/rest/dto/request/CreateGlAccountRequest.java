package com.inventory.accounting.rest.dto.request;

import com.inventory.accounting.domain.model.AccountType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class CreateGlAccountRequest {

  @NotBlank private String code;

  @NotBlank private String name;

  @NotNull private AccountType accountType;

  /** Defaults to true when omitted */
  private Boolean active;
}
