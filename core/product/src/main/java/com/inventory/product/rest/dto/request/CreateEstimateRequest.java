package com.inventory.product.rest.dto.request;

import lombok.Data;

/** Create an empty estimate document (same customer fields as a quotation). */
@Data
public class CreateEstimateRequest {

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
