package com.inventory.product.rest.dto.response;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * What a correction would do to a purchase invoice, worked out without saving it, so the operator
 * sees each figure that moves before confirming.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AmendInvoicePreviewResponse {
  /** The header as it is saved now. */
  private InvoiceHeaderFiguresDto saved;
  /** The header the correction would save. */
  private InvoiceHeaderFiguresDto corrected;
  /** Names of the figures that differ, e.g. {@code taxTreatment}, {@code taxTotal}. */
  private List<String> changedFields;
  /** True when saving would reverse the invoice's journal entry and post a corrected one. */
  private boolean journalReposted;
}
