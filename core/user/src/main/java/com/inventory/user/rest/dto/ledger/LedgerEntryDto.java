package com.inventory.user.rest.dto.ledger;

import com.inventory.user.domain.model.LedgerEntryType;
import com.inventory.user.domain.model.LedgerPartyType;
import com.inventory.user.domain.model.LedgerReferenceType;
import com.inventory.user.domain.model.LedgerSource;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class LedgerEntryDto {

  private String id;
  private String shopId;
  private LedgerPartyType partyType;
  private String partyId;
  /** Resolved vendor or customer name for display */
  private String partyName;
  /** Resolved counterparty shop name (vendor's shop when assigned) for display */
  private String counterpartyShopName;
  private String counterpartyShopId;
  /** Computed display name for Party column: vendor/customer when we're buyer, buyer shop when we're vendor */
  private String displayPartyName;
  /** Our role in this entry: BUYER (we owe) or VENDOR (they owe us) */
  private String roleInEntry;
  private BigDecimal amount;
  private LedgerEntryType type;
  private LedgerSource source;
  private String referenceId;
  private LedgerReferenceType referenceType;
  private String description;
  private String createdByUserId;
  private Instant createdAt;
}
