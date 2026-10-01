package com.inventory.product.rest.dto.response;

import com.inventory.product.domain.model.enums.StockEntryEstimateState;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class StockEntryEstimateListResponse {
  private List<StockEntryEstimateSummary> estimates;
  private long total;
  private int page;
  private int size;
  private int totalPages;

  @Data
  @NoArgsConstructor
  @AllArgsConstructor
  public static class StockEntryEstimateSummary {
    private String id;
    private String estimateNo;
    private StockEntryEstimateState state;
    private String vendorId;
    private String vendorInvoiceNo;
    private int itemCount;
    private BigDecimal invoiceTotal;
    private Instant updatedAt;
    private Instant createdAt;
    /** Any line carries HSN-rate fields (REGULAR); lock freezes instead of creating stock. */
    private boolean taxable;
    /** LOCKED REGULAR draft with no inventory yet — open in Product Entry to register. */
    private boolean awaitingConversion;
  }
}
