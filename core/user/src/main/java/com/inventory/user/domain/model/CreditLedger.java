package com.inventory.user.domain.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.math.BigDecimal;
import java.time.Instant;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "credit_ledger")
@CompoundIndex(name = "shop_party_idx", def = "{'shopId': 1, 'partyType': 1, 'partyId': 1, 'createdAt': -1}")
@CompoundIndex(name = "counterparty_idx", def = "{'counterpartyShopId': 1}")
public class CreditLedger {

  @Id
  private String id;
  private String shopId;
  private LedgerPartyType partyType;
  private String partyId;
  private String counterpartyShopId;
  private BigDecimal amount;
  private LedgerEntryType type;
  private LedgerSource source;
  private String referenceId;
  private LedgerReferenceType referenceType;
  private String description;
  private String createdByUserId;
  private Instant createdAt;
}
