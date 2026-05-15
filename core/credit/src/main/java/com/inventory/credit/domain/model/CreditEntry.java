package com.inventory.credit.domain.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "credit_entries")
@CompoundIndex(name = "shop_account_time", def = "{'shopId': 1, 'accountId': 1, 'createdAt': -1}")
public class CreditEntry {

  @Id private String id;

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
