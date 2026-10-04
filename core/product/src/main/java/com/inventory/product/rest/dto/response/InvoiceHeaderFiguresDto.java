package com.inventory.product.rest.dto.response;

import java.math.BigDecimal;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** A purchase invoice's header figures at one point in time, for showing before and after. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class InvoiceHeaderFiguresDto {
  private BigDecimal lineSubTotal;
  private BigDecimal taxTotal;
  private BigDecimal shippingCharge;
  private BigDecimal otherCharges;
  private BigDecimal overallDiscount;
  private BigDecimal roundOff;
  private BigDecimal invoiceTotal;
  /** EXCLUSIVE or INCLUSIVE; null on a bill recorded before the choice existed. */
  private String taxTreatment;
}
