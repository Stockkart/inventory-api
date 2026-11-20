package com.inventory.plan.service;

import com.inventory.plan.domain.model.PlanPaymentOrder;
import com.inventory.plan.domain.model.PlanTransaction;
import com.inventory.plan.domain.repository.PlanPaymentOrderRepository;
import com.inventory.plan.domain.repository.PlanTransactionRepository;
import com.inventory.plan.mapper.PlanTransactionMapper;
import com.inventory.plan.rest.dto.response.PlanTransactionResponse;
import com.inventory.plan.utils.constants.PlanPaymentConstants;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A shop's past purchases: its plan transactions merged with paid orders that never got one
 * (add-on-only top-ups, paid but not yet granted). Read-time merge only; nothing is written.
 */
@Service
public class PurchaseHistoryService {

  public static final String KIND_PLAN = "PLAN";
  public static final String KIND_ADD_ON_ONLY = "ADD_ON_ONLY";
  public static final String KIND_ASSIGNED = "ASSIGNED";

  /** Paid orders, including those refunded afterwards. */
  private static final List<String> HISTORY_STATUSES = List.of(
      PlanPaymentConstants.STATUS_PAID, PlanPaymentConstants.STATUS_FULFILLING,
      PlanPaymentConstants.STATUS_FULFILLED, PlanPaymentConstants.STATUS_FULFILMENT_FAILED,
      PlanPaymentConstants.STATUS_REFUNDED);

  @Autowired
  private PlanTransactionRepository planTransactionRepository;

  @Autowired
  private PlanPaymentOrderRepository planPaymentOrderRepository;

  @Autowired
  private PlanTransactionMapper planTransactionMapper;

  public List<PlanTransactionResponse> forShop(String shopId) {
    List<PlanTransaction> transactions =
        planTransactionRepository.findByShopId(shopId, Sort.by(Sort.Direction.DESC, "createdAt"));
    Map<String, PlanPaymentOrder> orders = new HashMap<>();
    for (PlanPaymentOrder order : planPaymentOrderRepository.findByShopIdAndStatusIn(shopId, HISTORY_STATUSES)) {
      orders.put(order.getId(), order);
    }

    List<PlanTransactionResponse> rows = new ArrayList<>();
    Set<String> covered = new HashSet<>();
    for (PlanTransaction tx : transactions) {
      PlanPaymentOrder order = tx.getPaymentOrderId() != null ? orders.get(tx.getPaymentOrderId()) : null;
      if (tx.getItems() != null) {
        // The transaction carries the order's snapshot, so it is the row for that order.
        if (order != null) {
          covered.add(order.getId());
        }
        rows.add(planTransactionMapper.toResponse(tx, order, KIND_PLAN, tx.getAmount()));
      } else if (isVerifiedPlanOrder(tx, order)) {
        // No snapshot, but a real order of this shop and plan: show what was charged, not the list price.
        covered.add(order.getId());
        rows.add(planTransactionMapper.toResponse(tx, order, KIND_PLAN, order.getAmount()));
      } else {
        // Payment ids on assigned rows come from the request body, so they prove nothing.
        rows.add(planTransactionMapper.toResponse(tx, null, KIND_ASSIGNED, null));
      }
    }
    for (PlanPaymentOrder order : orders.values()) {
      if (!covered.contains(order.getId())) {
        rows.add(planTransactionMapper.toResponse(order, order.isAddOnOnly() ? KIND_ADD_ON_ONLY : KIND_PLAN));
      }
    }
    rows.sort(Comparator.comparing(PlanTransactionResponse::getSortAt,
        Comparator.nullsLast(Comparator.reverseOrder())));
    return rows;
  }

  private static boolean isVerifiedPlanOrder(PlanTransaction tx, PlanPaymentOrder order) {
    return order != null && !order.isAddOnOnly() && order.getPlanId() != null
        && order.getPlanId().equals(tx.getPlanId());
  }
}
