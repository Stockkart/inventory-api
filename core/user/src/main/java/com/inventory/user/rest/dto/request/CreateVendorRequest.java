package com.inventory.user.rest.dto.request;

import com.inventory.common.tax.PurchaseTaxTreatment;
import lombok.Data;

@Data
public class CreateVendorRequest {
  private String name;
  private String contactEmail;
  private String contactPhone;
  private String address;
  private String companyName;
  private String businessType;
  private String gstinUin; // GSTIN or UIN (Unique Identification Number)
  private String dlNo;
  /** INCLUSIVE when this supplier bills at MRP with GST inside the line amount. */
  private PurchaseTaxTreatment defaultTaxTreatment;
  /** Optional. When set, links this vendor to a registered user account. */
  private String userId;
}
