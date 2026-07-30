package com.inventory.product.domain.model;

import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * Shop-level sale invoice defaults: print layout + field visibility + footer.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "shop_invoice_settings")
public class ShopInvoiceSettingsDocument {

  @Id
  private String id;

  @Indexed(unique = true)
  private String shopId;

  /** {@code NORMAL}, {@code DOT_MATRIX}, or {@code THERMAL_3INCH}. */
  private String defaultPrinterType = "NORMAL";

  private String footerNote = "";

  private InvoiceFieldVisibility regularFields;

  private InvoiceFieldVisibility basicFields;

  private Instant updatedAt;

  private String updatedByUserId;
}
