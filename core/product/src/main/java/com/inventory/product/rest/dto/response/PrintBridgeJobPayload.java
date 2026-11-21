package com.inventory.product.rest.dto.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * The body for the bridge's {@code POST /print}, exactly as the bridge reads it. The browser sends
 * it on unchanged and never reads it.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PrintBridgeJobPayload {
  /** INVOICE, ESTIMATE, CREDIT_NOTE or DEBIT_NOTE. The bridge sets the page length from it. */
  private String docType;
  private String docId;
  /** 0 asks the bridge for its own configured copies (e.g. original and customer copy). */
  private int copies;
  private String text;
}
