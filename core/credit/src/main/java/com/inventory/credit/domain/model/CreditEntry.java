package com.inventory.credit.domain.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "credit_entries")
@CompoundIndex(name = "shop_account_time", def = "{'shopId': 1, 'accountId': 1, 'createdAt': -1}")
public class CreditEntry {

  @Id private String id;

  /**
   * Stable business transaction identifier (UUID v4). Distinct from {@link #id}, which is the Mongo
   * storage id. The index is declared here as the canonical schema constraint, but is created
   * operationally — {@code spring.data.mongodb.auto-index-creation} is intentionally disabled, so
   * this annotation has no runtime effect. See the design spec, section 1.
   */
  @Indexed(unique = true, sparse = true)
  private String txnId;

  private String shopId;
  private String accountId;

  private CreditPartyType partyType;
  private String partyRefId;

  private CreditEntryType entryType;
  private CreditDirection direction;

  /** Always positive. */
  private BigDecimal amount;

  /** Running balance after applying this entry. */
  private BigDecimal balanceAfter;

  private String note;
  private String referenceType;
  private String referenceId;

  /** Optional idempotency key from caller. */
  private String sourceKey;

  /** Tender used for {@link CreditEntryType#SETTLEMENT} rows. */
  private String paymentMethod;

  private String bankRef;

  /** Business date supplied at settlement time (may differ from {@link #createdAt}). */
  private LocalDate txnDate;

  private String createdByUserId;
  private Instant createdAt;
}
