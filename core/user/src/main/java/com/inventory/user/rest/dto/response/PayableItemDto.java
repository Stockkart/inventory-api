package com.inventory.user.rest.dto.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Amount this shop owes to a vendor (viewing from buyer's shop).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PayableItemDto {

  private String vendorId;
  private String vendorName;
  /** Vendor's shop name when assigned (counterparty shop). */
  private String counterpartyShopName;
  private BigDecimal balance;
}
