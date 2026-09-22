package com.inventory.plugins.cafe;

import com.inventory.common.exception.ResourceNotFoundException;
import com.inventory.pluginengine.kot.CafeKotPort;
import com.inventory.pluginengine.kot.CafeKotTicket;
import com.inventory.pluginengine.kot.CafeKotTicketLine;
import com.inventory.plugins.cafe.domain.CafeKot;
import com.inventory.plugins.cafe.domain.CafeKotLine;
import com.inventory.plugins.cafe.domain.CafeKotRepository;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Adapts the cafe plugin's punch, cancel and ticket services to {@link CafeKotPort}: the only
 * view of a cafe kitchen ticket that {@code core/product} is allowed to see.
 */
@Component
public class CafeKotAdapter implements CafeKotPort {

  private final CafeKotRepository kotRepository;
  private final CafeKotPunchService punchService;
  private final CafeKotCancelService cafeKotCancelService;

  public CafeKotAdapter(
      CafeKotRepository kotRepository,
      CafeKotPunchService punchService,
      CafeKotCancelService cafeKotCancelService) {
    this.kotRepository = kotRepository;
    this.punchService = punchService;
    this.cafeKotCancelService = cafeKotCancelService;
  }

  @Override
  public String getVerticalId() {
    return "cafe";
  }

  @Override
  public List<CafeKotTicket> punch(
      String shopId, String userId, String purchaseId, String idempotencyKey) {
    return punchService.punch(shopId, userId, purchaseId, idempotencyKey).stream()
        .map(CafeKotAdapter::toTicket)
        .toList();
  }

  @Override
  public Optional<CafeKotTicket> findKot(String shopId, String kotId) {
    return kotRepository.findByIdAndShopId(kotId, shopId).map(CafeKotAdapter::toTicket);
  }

  @Override
  public List<CafeKotTicket> cancel(
      String shopId,
      String userId,
      String purchaseId,
      String lineRef,
      int fromQty,
      int toQty,
      String idempotencyKey) {
    return cafeKotCancelService
        .cancel(shopId, userId, purchaseId, lineRef, fromQty, toQty, idempotencyKey)
        .stream()
        .map(CafeKotAdapter::toTicket)
        .toList();
  }

  /**
   * Bumps {@code reprintCount} on an already-issued ticket and returns it, creating no new
   * ticket. The stamp a reprinted slip renders with is {@code core/product}'s concern
   * ({@code CafeKotService}), the same way it already decides {@code CANCELLED} for a cancel.
   */
  @Override
  public CafeKotTicket reprint(String shopId, String kotId, String idempotencyKey) {
    CafeKot kot =
        kotRepository
            .findByIdAndShopId(kotId, shopId)
            .orElseThrow(() -> new ResourceNotFoundException("CafeKot", "id", kotId));
    kot.setReprintCount(kot.getReprintCount() == null ? 1 : kot.getReprintCount() + 1);
    CafeKot saved = kotRepository.save(kot);
    return toTicket(saved);
  }

  private static CafeKotTicket toTicket(CafeKot kot) {
    return CafeKotTicket.builder()
        .kotId(kot.getId())
        .shopId(kot.getShopId())
        .purchaseId(kot.getPurchaseId())
        .kotNo(kot.getKotNo())
        .department(kot.getDepartment())
        .roundNo(kot.getRoundNo())
        .kind(kot.getKind() == null ? null : kot.getKind().name())
        .status(kot.getStatus() == null ? null : kot.getStatus().name())
        .tableLabel(kot.getTableLabel())
        .tokenNo(kot.getTokenNo())
        .businessDate(kot.getBusinessDate())
        .reprintCount(kot.getReprintCount())
        .lines(
            kot.getLines() == null
                ? List.of()
                : kot.getLines().stream().map(CafeKotAdapter::toLine).toList())
        .build();
  }

  private static CafeKotTicketLine toLine(CafeKotLine line) {
    return CafeKotTicketLine.builder()
        .lineId(line.getLineId())
        .name(line.getName())
        .quantity(line.getQuantity())
        .note(line.getNote())
        .build();
  }
}
