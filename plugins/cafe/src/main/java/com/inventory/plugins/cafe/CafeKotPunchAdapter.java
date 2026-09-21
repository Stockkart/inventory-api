package com.inventory.plugins.cafe;

import com.inventory.pluginengine.kot.CafeKotPunchPort;
import com.inventory.pluginengine.kot.CafeKotTicket;
import com.inventory.pluginengine.kot.CafeKotTicketLine;
import com.inventory.plugins.cafe.domain.CafeKot;
import com.inventory.plugins.cafe.domain.CafeKotLine;
import com.inventory.plugins.cafe.domain.CafeKotRepository;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Adapts {@link CafeKotPunchService} to {@link CafeKotPunchPort}: the only thing {@code
 * core/product} is allowed to see of the cafe cart-punch flow.
 */
@Component
public class CafeKotPunchAdapter implements CafeKotPunchPort {

  private final CafeKotPunchService punchService;
  private final CafeKotRepository kotRepository;

  public CafeKotPunchAdapter(CafeKotPunchService punchService, CafeKotRepository kotRepository) {
    this.punchService = punchService;
    this.kotRepository = kotRepository;
  }

  @Override
  public String getVerticalId() {
    return "cafe";
  }

  @Override
  public List<CafeKotTicket> punch(
      String shopId, String userId, String purchaseId, String idempotencyKey) {
    return punchService.punch(shopId, userId, purchaseId, idempotencyKey).stream()
        .map(CafeKotPunchAdapter::toTicket)
        .toList();
  }

  @Override
  public Optional<CafeKotTicket> findKot(String shopId, String kotId) {
    return kotRepository.findByIdAndShopId(kotId, shopId).map(CafeKotPunchAdapter::toTicket);
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
        .businessDate(kot.getBusinessDate())
        .lines(kot.getLines() == null ? List.of() : kot.getLines().stream().map(CafeKotPunchAdapter::toLine).toList())
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
