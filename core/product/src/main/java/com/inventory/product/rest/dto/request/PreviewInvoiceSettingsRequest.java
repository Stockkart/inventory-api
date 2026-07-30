package com.inventory.product.rest.dto.request;

import com.inventory.product.domain.model.InvoiceFieldVisibility;
import lombok.Data;

/**
 * Draft invoice settings used for live PDF preview (not persisted).
 */
@Data
public class PreviewInvoiceSettingsRequest {
  private String defaultPrinterType;
  private String footerNote;
  private InvoiceFieldVisibility regularFields;
  private InvoiceFieldVisibility basicFields;

  /** {@code REGULAR} or {@code BASIC} — which field set to apply in the preview. */
  private String previewBillingMode;

  /** Printer layout for this preview render; falls back to {@code defaultPrinterType}. */
  private String previewPrinterType;
}
