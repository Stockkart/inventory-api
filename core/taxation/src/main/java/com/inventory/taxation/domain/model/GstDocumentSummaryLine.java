package com.inventory.taxation.domain.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Document count summary for GSTR-1 docs tab.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GstDocumentSummaryLine {

  private String natureOfDocument;
  private String srNoFrom;
  private String srNoTo;
  private Integer totalNumber;
  private Integer cancelled;
}
