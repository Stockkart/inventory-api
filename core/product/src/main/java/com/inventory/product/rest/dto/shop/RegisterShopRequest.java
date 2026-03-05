package com.inventory.product.rest.dto.shop;

import com.inventory.product.domain.model.ShopType;
import lombok.Data;

@Data
public class RegisterShopRequest {

  private String name;
  private String businessId;
  private LocationDto location;
  private String contactEmail;
  private String contactPhone;
  private String gstinNo; // Optional: GSTIN number
  private String fssai; // Optional: FSSAI license number
  private String dlNo; // Required for pharmacy/pharm business types
  private String panNo; // Optional: PAN number
  private String sgst; // Optional: State GST
  private String cgst; // Optional: Central GST
  private String tagline; // Optional: Shop tagline, banner word, or highlight text
  /** Shop type: RETAILER, DISTRIBUTOR, or WHOLESALER. For retailer, default price is MRP (tax-inclusive). */
  private ShopType shopType;
}

