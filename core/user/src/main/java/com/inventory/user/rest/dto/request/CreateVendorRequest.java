package com.inventory.user.rest.dto.request;

import com.inventory.common.gst.PostalAddress;

import com.inventory.common.constants.PurchaseTaxTreatment;
import lombok.Data;

@Data
public class CreateVendorRequest {
  private String name;
  private String contactEmail;
  private String contactPhone;
  private String address;
  /** The address as fields. Either a valid GSTIN or {@code postalAddress.stateCode} is required. */
  private PostalAddress postalAddress;
  private String companyName;
  private String businessType;
  private String gstinUin; // GSTIN or UIN (Unique Identification Number)
  private String dlNo;
  /** INCLUSIVE when this supplier bills at MRP with GST inside the line amount. */
  private PurchaseTaxTreatment defaultTaxTreatment;
  /** Optional. When set, links this vendor to a registered user account. */
  private String userId;
}
