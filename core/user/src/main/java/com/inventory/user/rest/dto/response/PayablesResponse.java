package com.inventory.user.rest.dto.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Response when a buyer shop views amounts to pay to vendors.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PayablesResponse {

  private List<PayableItemDto> payables;
}
