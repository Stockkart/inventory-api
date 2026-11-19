package com.inventory.user.rest.dto.request;

import com.inventory.common.gst.PostalAddress;

import com.inventory.common.constants.PurchaseTaxTreatment;
import java.time.LocalDate;
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
  /** Last day the D.L. is valid (yyyy-MM-dd). Required when a D.L. No. is given. */
  private LocalDate dlExpiry;
  /** INCLUSIVE when this supplier bills at MRP with GST inside the line amount. */
  private PurchaseTaxTreatment defaultTaxTreatment;
  /** Optional. When set, links this vendor to a registered user account. */
  private String userId;
}
