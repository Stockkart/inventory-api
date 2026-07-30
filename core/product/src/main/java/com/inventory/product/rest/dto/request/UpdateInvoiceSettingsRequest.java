package com.inventory.product.rest.dto.request;

import com.inventory.product.domain.model.InvoiceFieldVisibility;
import lombok.Data;

/**
 * Partial update for shop invoice settings. Null top-level fields are left unchanged.
 */
@Data
public class UpdateInvoiceSettingsRequest {
  private String defaultPrinterType;
  private String footerNote;
  private InvoiceFieldVisibility regularFields;
  private InvoiceFieldVisibility basicFields;
}
