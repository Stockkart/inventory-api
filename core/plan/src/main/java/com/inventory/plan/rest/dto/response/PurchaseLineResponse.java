package com.inventory.plan.rest.dto.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/** One priced line of a past purchase, as the shop was charged. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PurchaseLineResponse {

  private String type;
  private String code;
  private String name;
  private int quantity;
  private BigDecimal unitPrice;
  private BigDecimal discount;
  private String voucherCode;
  private BigDecimal walletCredit;
  private BigDecimal lineTotal;
}
