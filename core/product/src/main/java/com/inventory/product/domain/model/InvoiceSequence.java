package com.inventory.product.domain.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * Per-shop sequence for generating short, incremental invoice numbers.
 * One document per shop; seq is atomically incremented.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "invoice_sequences")
public class InvoiceSequence {

  @Id
  private String shopId;
  private long seq;
}
