package com.inventory.user.rest.dto.ledger;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Amount a customer owes to this shop (from sales on credit).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CustomerReceivableItemDto {

  private String customerId;
  private String customerName;
  private String customerPhone;
  private BigDecimal balance;
}
