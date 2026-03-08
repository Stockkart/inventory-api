package com.inventory.user.rest.dto.ledger;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * One shop's outstanding balance owed to this vendor (when viewing from vendor's shop).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ReceivableItemDto {

  private String buyerShopId;
  private String buyerShopName;
  /** Display name for who has to pay: shop name and/or owner/contact name */
  private String buyerPayerName;
  private String vendorId;
  private String vendorName;
  private BigDecimal balance;
}
