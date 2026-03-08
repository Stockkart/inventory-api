package com.inventory.user.rest.dto.ledger;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Response when a vendor's shop views amounts to collect from buyer shops.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ReceivablesResponse {

  private List<ReceivableItemDto> receivables;
}
