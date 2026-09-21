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
 * Adapts {@link CafeKotRepository} to {@link CafeKotPunchPort}: the only thing {@code
 * core/product} is allowed to see of a cafe kitchen ticket, so it can render its document.
 */
@Component
public class CafeKotDocumentAdapter implements CafeKotPunchPort {

  private final CafeKotRepository kotRepository;

  public CafeKotDocumentAdapter(CafeKotRepository kotRepository) {
    this.kotRepository = kotRepository;
  }

  @Override
  public String getVerticalId() {
    return "cafe";
  }

  @Override
  public Optional<CafeKotTicket> findKot(String shopId, String kotId) {
    return kotRepository.findByIdAndShopId(kotId, shopId).map(CafeKotDocumentAdapter::toTicket);
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
        .lines(
            kot.getLines() == null
                ? List.of()
                : kot.getLines().stream().map(CafeKotDocumentAdapter::toLine).toList())
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
