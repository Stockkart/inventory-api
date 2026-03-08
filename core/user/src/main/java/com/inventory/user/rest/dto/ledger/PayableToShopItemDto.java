package com.inventory.user.rest.dto.ledger;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Amount this shop owes to another shop (when we bought from them as a customer on credit).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PayableToShopItemDto {

  private String sellerShopId;
  private String sellerShopName;
  /** Customer ID at the seller's shop (needed for recording payment) */
  private String customerId;
  private BigDecimal balance;
}
