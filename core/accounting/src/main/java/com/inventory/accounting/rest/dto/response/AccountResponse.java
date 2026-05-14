package com.inventory.accounting.rest.dto.response;

import com.inventory.accounting.domain.model.AccountType;
import com.inventory.accounting.domain.model.NormalBalance;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AccountResponse {
  private String id;
  private String code;
  private String name;
  private AccountType type;
  private NormalBalance normalBalance;
  private String parentCode;
  private boolean system;
  private boolean active;
  private Instant createdAt;
  private Instant updatedAt;
}
