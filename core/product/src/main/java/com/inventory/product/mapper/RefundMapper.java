package com.inventory.product.mapper;

import com.inventory.product.rest.dto.response.RefundListResponse;
import com.inventory.product.rest.dto.response.RefundResponse;
import com.inventory.product.rest.dto.response.RefundSummaryDto;
import org.mapstruct.Mapper;
import org.mapstruct.ReportingPolicy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collections;
import java.util.List;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface RefundMapper {

  /**
   * Build paginated refund list response. Use empty list for empty result.
   */
  default RefundListResponse toRefundListResponse(List<RefundSummaryDto> refunds,
      int page, int limit, long total, int totalPages) {
    RefundListResponse response = new RefundListResponse();
    response.setRefunds(refunds != null ? refunds : Collections.emptyList());
    response.setPage(page);
    response.setLimit(limit);
    response.setTotal(total);
    response.setTotalPages(totalPages);
    return response;
  }

  /**
   * Build refund response after processing.
   */
  default RefundResponse toRefundResponse(String refundId, String creditNoteNo, String purchaseId,
      List<RefundResponse.RefundedItem> refundedItems, BigDecimal refundAmount, Instant createdAt) {
    RefundResponse response = new RefundResponse();
    response.setRefundId(refundId);
    response.setCreditNoteNo(creditNoteNo);
    response.setPurchaseId(purchaseId);
    response.setRefundedItems(refundedItems != null ? refundedItems : Collections.emptyList());
    response.setRefundAmount(refundAmount);
    response.setTotalItemsRefunded(refundedItems != null ? refundedItems.size() : 0);
    response.setCreatedAt(createdAt);
    return response;
  }
}
