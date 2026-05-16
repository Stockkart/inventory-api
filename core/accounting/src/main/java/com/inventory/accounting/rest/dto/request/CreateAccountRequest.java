package com.inventory.accounting.rest.dto.request;

import com.inventory.accounting.domain.model.AccountType;
import com.inventory.accounting.domain.model.NormalBalance;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class CreateAccountRequest {

  @NotBlank private String code;
  @NotBlank private String name;
  @NotNull private AccountType type;
  private NormalBalance normalBalance;
}
