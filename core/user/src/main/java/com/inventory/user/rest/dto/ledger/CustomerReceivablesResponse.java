package com.inventory.user.rest.dto.ledger;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Response when a seller shop views amounts to collect from customers.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CustomerReceivablesResponse {

  private List<CustomerReceivableItemDto> receivables;
}
