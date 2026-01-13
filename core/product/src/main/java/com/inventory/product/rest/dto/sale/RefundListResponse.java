package com.inventory.product.rest.dto.sale;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Response DTO for refund list with pagination.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RefundListResponse {
  /**
   * List of refunds.
   */
  private List<RefundSummaryDto> refunds;

  /**
   * Current page number (1-based).
   */
  private int page;

  /**
   * Page size (limit).
   */
  private int limit;

  /**
   * Total number of refunds.
   */
  private long total;

  /**
   * Total number of pages.
   */
  private int totalPages;
}

