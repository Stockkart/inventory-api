package com.inventory.plugins.cafe;

import com.inventory.pluginengine.order.KotLineView;
import com.inventory.pluginengine.order.KotView;
import com.inventory.pluginengine.order.RunningOrderLineView;
import com.inventory.pluginengine.order.RunningOrderView;
import com.inventory.plugins.cafe.domain.CafeKot;
import com.inventory.plugins.cafe.domain.CafeOrder;
import java.util.List;

/** Domain documents are plugin-private; core sees only these views. */
final class CafeOrderMapper {

  private CafeOrderMapper() {}

  static RunningOrderView toView(CafeOrder order) {
    return RunningOrderView.builder()
        .orderId(order.getId())
        .shopId(order.getShopId())
        .orderNo(order.getOrderNo())
        .orderType(order.getOrderType() != null ? order.getOrderType().name() : null)
        .tableLabel(order.getTableLabel())
        .tokenNo(order.getTokenNo())
        .status(order.getStatus() != null ? order.getStatus().name() : null)
        .purchaseId(order.getPurchaseId())
        .businessDate(order.getBusinessDate())
        .lines(
            order.getLines().stream()
                .map(
                    line ->
                        RunningOrderLineView.builder()
                            .lineId(line.getLineId())
                            .sellableRef(line.getSellableRef())
                            .name(line.getName())
                            .quantity(line.getQuantity())
                            .note(line.getNote())
                            .department(line.getDepartment())
                            .kotId(line.getKotId())
                            .status(line.getStatus() != null ? line.getStatus().name() : null)
                            .build())
                .toList())
        .build();
  }

  static KotView toView(CafeKot kot) {
    return KotView.builder()
        .kotId(kot.getId())
        .shopId(kot.getShopId())
        .orderId(kot.getOrderId())
        .kotNo(kot.getKotNo())
        .department(kot.getDepartment())
        .roundNo(kot.getRoundNo())
        .status(kot.getStatus() != null ? kot.getStatus().name() : null)
        .voidReason(kot.getVoidReason())
        .reprintCount(kot.getReprintCount())
        .businessDate(kot.getBusinessDate())
        .lines(
            kot.getLines().stream()
                .map(
                    line ->
                        KotLineView.builder()
                            .lineId(line.getLineId())
                            .name(line.getName())
                            .quantity(line.getQuantity())
                            .note(line.getNote())
                            .build())
                .toList())
        .build();
  }

  static List<KotView> toKotViews(List<CafeKot> kots) {
    return kots.stream().map(CafeOrderMapper::toView).toList();
  }
}
