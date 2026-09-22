package com.inventory.plugins.cafe;

import com.inventory.pluginengine.kot.CafeKotPort;
import com.inventory.pluginengine.kot.CafeKotTab;
import com.inventory.pluginengine.kot.CafeKotTabLine;
import com.inventory.pluginengine.kot.CafeKotTicket;
import com.inventory.pluginengine.kot.CafeKotTicketLine;
import com.inventory.plugins.cafe.domain.CafeKot;
import com.inventory.plugins.cafe.domain.CafeKotLine;
import com.inventory.plugins.cafe.domain.CafeKotRepository;
import com.inventory.plugins.cafe.domain.CafeTab;
import com.inventory.plugins.cafe.domain.CafeTabLine;
import com.inventory.common.exception.ResourceNotFoundException;
import java.util.List;
import java.util.Optional;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Adapts the cafe plugin's tab, flush and ticket services to {@link CafeKotPort}: the only view
 * of a cafe kitchen ticket or tab that {@code core/product} is allowed to see.
 *
 * <p>Renamed from {@code CafeKotDocumentAdapter}: it now composes and sends tickets, not just
 * reads them back for rendering.
 *
 * <p>{@code CafeFlushService} is injected lazily: it depends (through {@code CartTotalsPort}) on
 * {@code core/product}'s checkout wiring, which itself depends on {@code PluginRegistry} — the
 * very thing that constructs this plugin's beans. Composing tickets happens well after startup,
 * so deferring that one edge is enough to keep the graph acyclic without changing what any of
 * these collaborators actually do.
 */
@Component
public class CafeKotAdapter implements CafeKotPort {

  private final CafeKotRepository kotRepository;
  private final CafeTabService cafeTabService;
  private final CafeFlushService cafeFlushService;
  private final CafeKotCancelService cafeKotCancelService;

  public CafeKotAdapter(
      CafeKotRepository kotRepository,
      CafeTabService cafeTabService,
      @Lazy CafeFlushService cafeFlushService,
      CafeKotCancelService cafeKotCancelService) {
    this.kotRepository = kotRepository;
    this.cafeTabService = cafeTabService;
    this.cafeFlushService = cafeFlushService;
    this.cafeKotCancelService = cafeKotCancelService;
  }

  @Override
  public String getVerticalId() {
    return "cafe";
  }

  @Override
  public Optional<CafeKotTicket> findKot(String shopId, String kotId) {
    return kotRepository.findByIdAndShopId(kotId, shopId).map(CafeKotAdapter::toTicket);
  }

  @Override
  public List<CafeKotTab> listTabs(String shopId, String userId) {
    return cafeTabService.list(shopId, userId).stream().map(CafeKotAdapter::toTab).toList();
  }

  @Override
  public CafeKotTab openTab(String shopId, String userId) {
    return toTab(cafeTabService.open(shopId, userId));
  }

  @Override
  public CafeKotTab upsertTabLine(
      String shopId,
      String userId,
      String tabId,
      String lineRef,
      String sellableRef,
      int quantity,
      String note) {
    CafeTab tab =
        StringUtils.hasText(lineRef)
            ? cafeTabService.updateLine(shopId, userId, tabId, lineRef, quantity, note)
            : cafeTabService.addLine(shopId, userId, tabId, sellableRef, quantity, note);
    return toTab(tab);
  }

  @Override
  public CafeKotTab removeTabLine(String shopId, String userId, String tabId, String lineRef) {
    return toTab(cafeTabService.removeLine(shopId, userId, tabId, lineRef));
  }

  @Override
  public void closeTab(String shopId, String userId, String tabId) {
    cafeTabService.close(shopId, userId, tabId);
  }

  @Override
  public List<CafeKotTicket> flush(
      String shopId, String userId, String tabId, String targetPurchaseId, String idempotencyKey) {
    return cafeFlushService.flush(shopId, userId, tabId, targetPurchaseId, idempotencyKey).stream()
        .map(CafeKotAdapter::toTicket)
        .toList();
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
   * ({@code CafeKotService}), the same way it already decides {@code CANCELLED} for a voided one.
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

  private static CafeKotTab toTab(CafeTab tab) {
    return CafeKotTab.builder()
        .id(tab.getId())
        .tokenNo(tab.getTokenNo())
        .status(tab.getStatus() == null ? null : tab.getStatus().name())
        .lines(
            tab.getLines() == null
                ? List.of()
                : tab.getLines().stream().map(CafeKotAdapter::toTabLine).toList())
        .build();
  }

  private static CafeKotTabLine toTabLine(CafeTabLine line) {
    return CafeKotTabLine.builder()
        .lineRef(line.getLineRef())
        .sellableRef(line.getSellableRef())
        .name(line.getName())
        .quantity(line.getQuantity())
        .note(line.getNote())
        .department(line.getDepartment())
        .build();
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
