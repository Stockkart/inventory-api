package com.inventory.credit.domain.model;

import java.math.BigDecimal;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "credit_accounts")
@CompoundIndex(
    name = "shop_party_unique",
    def = "{'shopId': 1, 'partyType': 1, 'partyRefId': 1}",
    unique = true)
public class CreditAccount {

  @Id private String id;

  private String shopId;
  private CreditPartyType partyType;
  private String partyRefId;
  private String partyDisplayName;
  private String partyPhone;

  /** Positive means party owes shop; negative means advance/overpayment. */
  private BigDecimal currentBalance;
  private CreditBalanceStatus status;

  private Instant createdAt;
  private Instant updatedAt;
  private Instant lastEntryAt;
}
