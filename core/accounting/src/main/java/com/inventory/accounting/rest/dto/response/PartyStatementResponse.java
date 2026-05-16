package com.inventory.accounting.rest.dto.response;

import com.inventory.accounting.domain.model.PartyType;
import java.math.BigDecimal;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Subsidiary-ledger detail (a.k.a. vendor or customer statement). The {@code openingBalance} is the
 * party-oriented balance immediately before {@code from} (or zero when {@code from} is not set),
 * and {@code closingBalance} is the balance after the last entry on the current page.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PartyStatementResponse {
  private PartyType partyType;
  private String partyRefId;
  private String partyDisplayName;
  private BigDecimal openingBalance;
  private BigDecimal closingBalance;
  private List<PartyStatementEntryResponse> entries;
  private int page;
  private int size;
  private long totalItems;
  private int totalPages;
}
