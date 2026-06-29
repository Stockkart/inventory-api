package com.inventory.product.service;

import com.inventory.product.rest.dto.request.UpdateInventoryRequest;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.springframework.util.StringUtils;

/** Extracts RBAC field keys from a partial inventory update request. */
public final class ProductSearchUpdateFields {

  private ProductSearchUpdateFields() {}

  public static Set<String> fromUpdateRequest(UpdateInventoryRequest request) {
    Set<String> fields = new LinkedHashSet<>();
    if (request == null) {
      return fields;
    }
    if (request.getBarcode() != null) fields.add("barcode");
    if (request.getName() != null) fields.add("name");
    if (request.getDescription() != null) fields.add("description");
    if (request.getCompanyName() != null) fields.add("companyName");
    if (request.getBusinessType() != null) fields.add("businessType");
    if (request.getLocation() != null) fields.add("location");
    if (request.getMaximumRetailPrice() != null) fields.add("maximumRetailPrice");
    if (request.getCostPrice() != null) fields.add("costPrice");
    if (request.getPriceToRetail() != null) fields.add("priceToRetail");
    if (request.getSellingPrice() != null) fields.add("sellingPrice");
    if (request.getRates() != null) fields.add("rates");
    if (StringUtils.hasText(request.getDefaultRate())) fields.add("defaultRate");
    if (request.getSaleAdditionalDiscount() != null) fields.add("saleAdditionalDiscount");
    if (request.getPurchaseAdditionalDiscount() != null) fields.add("purchaseAdditionalDiscount");
    if (request.getPurchaseSchemeType() != null) fields.add("purchaseSchemeType");
    if (request.getPurchaseSchemePayFor() != null) fields.add("purchaseSchemePayFor");
    if (request.getPurchaseSchemeFree() != null) fields.add("purchaseSchemeFree");
    if (request.getPurchaseSchemePercentage() != null) fields.add("purchaseSchemePercentage");
    if (request.getSgst() != null) fields.add("sgst");
    if (request.getCgst() != null) fields.add("cgst");
    if (request.getItemType() != null) fields.add("itemType");
    if (request.getItemTypeDegree() != null) fields.add("itemTypeDegree");
    if (request.getDiscountApplicable() != null) fields.add("discountApplicable");
    if (request.getPurchaseDate() != null) fields.add("purchaseDate");
    if (request.getExpiryDate() != null) fields.add("expiryDate");
    if (request.getHsn() != null) fields.add("hsn");
    if (request.getBatchNo() != null) fields.add("batchNo");
    if (request.getVendorId() != null) fields.add("vendorId");
    if (request.getBillingMode() != null) fields.add("billingMode");
    if (request.getSchemeType() != null) fields.add("schemeType");
    if (request.getScheme() != null) fields.add("scheme");
    if (request.getSchemePayFor() != null) fields.add("schemePayFor");
    if (request.getSchemeFree() != null) fields.add("schemeFree");
    if (request.getSchemePercentage() != null) fields.add("schemePercentage");
    if (request.getBaseUnit() != null) fields.add("baseUnit");
    if (request.getUnitConversions() != null) fields.add("unitsPerPack");
    if (request.getThresholdCount() != null) fields.add("thresholdCount");
    Map<String, Object> vertical = request.getVerticalFields();
    if (vertical != null) {
      for (String key : vertical.keySet()) {
        if (StringUtils.hasText(key)) {
          fields.add(key);
        }
      }
    }
    return fields;
  }
}
