package com.inventory.product.domain.model;

import java.time.Instant;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * Per-shop invoice / note sequence. REGULAR docs ({@code _id = shopId}) also store format and
 * financial-year state. BASIC/CN/VCN docs only use {@code seq}.
 */
@Data
@NoArgsConstructor
@Document(collection = "invoice_sequences")
public class InvoiceSequence {

  @Id
  private String shopId;
  private long seq;

  /** Static prefix for REGULAR invoices (e.g. {@code INV-}, {@code SL-}, {@code INV/PH/}). */
  private String prefix;

  /** Zero-pad width for the numeric counter. */
  private Integer padLength;

  /** {@code STOCKKART} or {@code MIGRATED}. */
  private String source;

  /** Set after the first issued REGULAR invoice (or on backfill for live shops). */
  private Instant lockedAt;

  /** Indian FY label this {@code seq} belongs to, e.g. {@code 2026-27}. */
  private String fyLabel;
}
