package com.inventory.accounting.domain.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "acct_gl_accounts")
@CompoundIndex(name = "shop_code_unique", def = "{'shopId': 1, 'code': 1}", unique = true)
public class GlAccount {

  @Id private String id;
  /** Shop that owns this account in its chart */
  private String shopId;
  /** Immutable code used in APIs / imports (unique per shop) */
  private String code;
  private String name;
  private AccountType accountType;
  /** When true, seed row — do not delete. */
  private boolean systemAccount;
  private boolean active;
  private Instant createdAt;
}
