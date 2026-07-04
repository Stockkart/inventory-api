package com.inventory.product.rest.dto.request;

import lombok.Data;

@Data
public class CreateQuotationRequest {

  private String businessType;
  private String customerName;
  private String customerAddress;
  private String customerPhone;
  private String customerEmail;
  private String customerGstin;
  private String customerDlNo;
  private String customerPan;
  private String customerUserId;
}
