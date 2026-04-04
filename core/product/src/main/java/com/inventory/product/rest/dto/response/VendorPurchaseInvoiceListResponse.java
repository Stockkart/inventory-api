package com.inventory.product.rest.dto.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class VendorPurchaseInvoiceListResponse {
  private List<VendorPurchaseInvoiceSummaryDto> invoices;
  private PageMeta page;
}
