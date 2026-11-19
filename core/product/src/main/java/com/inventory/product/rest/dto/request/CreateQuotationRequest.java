package com.inventory.product.rest.dto.request;

import java.time.LocalDate;
import lombok.Data;

@Data
public class CreateQuotationRequest {

  private String businessType;
  private String customerId;
  private String customerName;
  private String customerAddress;
  private String customerPhone;
  private String customerEmail;
  private String customerGstin;
  private String customerDlNo;
  /** Last day the D.L. is valid; needed when this request gives the customer a new D.L. No. */
  private LocalDate customerDlExpiry;
  private String customerPan;
  private String customerPartyType;
  private String customerUserId;
}
