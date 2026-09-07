package com.inventory.user.rest.dto.request;

import com.inventory.common.tax.PurchaseTaxTreatment;
import lombok.Data;

@Data
public class UpdateVendorRequest {
  private String name;
  private String contactEmail;
  private String contactPhone;
  private String address;
  private String companyName;
  private String businessType;
  private String gstinUin;
  private String dlNo;
  /** INCLUSIVE when this supplier bills at MRP with GST inside the line amount. */
  private PurchaseTaxTreatment defaultTaxTreatment;
}
