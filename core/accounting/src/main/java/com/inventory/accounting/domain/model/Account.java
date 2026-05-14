package com.inventory.accounting.domain.model;

import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * Chart-of-accounts entry, scoped per shop. {@code system} accounts are seeded by {@link
 * com.inventory.accounting.service.ChartOfAccountsSeeder} and cannot be renamed or deleted.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "coa_accounts")
@CompoundIndex(name = "shop_code_unique", def = "{'shopId': 1, 'code': 1}", unique = true)
public class Account {

  @Id private String id;

  private String shopId;

  /** Hierarchical numeric identifier, e.g. {@code 1100} for Cash in Hand. */
  private String code;

  /** Human-readable name (system accounts use canonical English names). */
  private String name;

  private AccountType type;
  private NormalBalance normalBalance;

  /** Optional parent account code for tree views; null for top-level. */
  private String parentCode;

  /** True for accounts seeded by the system; rename/delete blocked. */
  private boolean system;

  /** When false, the account is hidden from selectors but historical postings remain. */
  private boolean active;

  private Instant createdAt;
  private Instant updatedAt;
}
