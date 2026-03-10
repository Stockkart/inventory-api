package com.inventory.user.rest.dto.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Response when this shop views amounts to pay to other shops (bought from them as customer on credit).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PayablesToShopsResponse {

  private List<PayableToShopItemDto> payables;
}
