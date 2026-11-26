package com.inventory.hr.rest.dto.response;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One page of the Join requests tab (worker-app R4.1, R4.2), newest first, plus the shop's pending
 * count for the tab label whatever status the page shows. The paging fields sit at the top level,
 * {@code page} being the zero-based page echoed back, as {@code attendance-protocol/src/worker.types.ts}
 * ({@code JoinRequestsPageResponse}) and the design's manager-inbox table say; this response does
 * not use the {@link PageMeta} block of the HR staff pages.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class JoinRequestsPageResponse {
  private List<JoinRequestRow> items;
  /** Pending join and link requests for the shop, for the tab label (R4.1). */
  private long pendingCount;
  /** Zero-based page echoed back. */
  private int page;
  /** Page size used. */
  private int size;
  /** Rows in the requested status for the shop. */
  private long totalItems;
  private int totalPages;
}
