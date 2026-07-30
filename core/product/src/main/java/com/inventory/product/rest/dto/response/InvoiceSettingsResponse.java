package com.inventory.product.rest.dto.response;

import com.inventory.product.domain.model.InvoiceFieldVisibility;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class InvoiceSettingsResponse {
  private String shopId;
  private String defaultPrinterType;
  private String footerNote;
  private InvoiceFieldVisibility regularFields;
  private InvoiceFieldVisibility basicFields;
}
