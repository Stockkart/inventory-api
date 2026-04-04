package com.inventory.product.rest.dto.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Optional vendor invoice header from OCR / parse. All fields optional for backward compatibility.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ParsedVendorInvoiceDto {
  private String invoiceNo;
  private String invoiceDate;
  private BigDecimal lineSubTotal;
  private BigDecimal taxTotal;
  private BigDecimal shippingCharge;
  private BigDecimal otherCharges;
  private BigDecimal roundOff;
  private BigDecimal invoiceTotal;
}
