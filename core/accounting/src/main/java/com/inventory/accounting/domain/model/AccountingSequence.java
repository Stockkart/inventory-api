package com.inventory.accounting.domain.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * Per-shop monotonically-increasing counter used to mint journal entry numbers atomically via
 * Mongo {@code findAndModify}. {@code id} is the sequence key (typically the shop id).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "accounting_sequences")
public class AccountingSequence {

  @Id private String id;
  private long seq;
}
