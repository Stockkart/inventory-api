package com.inventory.plan.service;

import com.inventory.plan.domain.model.PlanPaymentOrder;
import com.inventory.plan.domain.model.PlanTransaction;
import com.inventory.plan.domain.repository.PlanPaymentOrderRepository;
import com.inventory.plan.domain.repository.PlanTransactionRepository;
import com.inventory.plan.mapper.PlanTransactionMapper;
import com.inventory.plan.rest.dto.response.PlanTransactionResponse;
import com.inventory.plan.utils.constants.PlanPaymentConstants;
import com.inventory.plan.utils.constants.PurchaseKindConstants;
import java.math.BigDecimal;
import com.inventory.plan.utils.constants.PlanConstants;
import com.inventory.plan.utils.PlanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A shop's past purchases: its plan transactions merged with paid orders that never got one
 * (add-on-only top-ups, paid but not yet granted). Read-time merge only; nothing is written.
 */
@Service
public class PurchaseHistoryService {

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
    // Snapshot transactions own their orders, whatever else points at them.
    for (PlanTransaction tx : transactions) {
      if (tx.getItems() != null && tx.getPaymentOrderId() != null && orders.containsKey(tx.getPaymentOrderId())) {
        covered.add(tx.getPaymentOrderId());
      }
    }
    // Snapshot-less transactions claim an unclaimed order oldest first, so the outcome is stable.
    List<PlanTransaction> claimOrder = new ArrayList<>(transactions);
    claimOrder.sort(Comparator.comparing(PlanTransaction::getCreatedAt, Comparator.nullsLast(Comparator.<Instant>naturalOrder()))
        .thenComparing(PlanTransaction::getId, Comparator.nullsLast(Comparator.<String>naturalOrder())));
    Set<PlanTransaction> claimed = Collections.newSetFromMap(new IdentityHashMap<>());
    for (PlanTransaction tx : claimOrder) {
      PlanPaymentOrder order = tx.getPaymentOrderId() != null ? orders.get(tx.getPaymentOrderId()) : null;
      if (tx.getItems() == null && isVerifiedPlanOrder(tx, order) && covered.add(order.getId())) {
        claimed.add(tx);
      }
    }
    for (PlanTransaction tx : transactions) {
      PlanPaymentOrder order = tx.getPaymentOrderId() != null ? orders.get(tx.getPaymentOrderId()) : null;
      if (tx.getItems() != null) {
        rows.add(transactionRow(tx, order, PurchaseKindConstants.PLAN));
      } else if (claimed.contains(tx)) {
        rows.add(transactionRow(tx, order, PurchaseKindConstants.PLAN));
      } else {
        // Payment ids on assigned rows come from the request body, so they prove nothing.
        rows.add(transactionRow(tx, null, PurchaseKindConstants.ASSIGNED));
      }
    }
    for (PlanPaymentOrder order : orders.values()) {
      if (!covered.contains(order.getId())) {
        rows.add(orderRow(order, order.isAddOnOnly() ? PurchaseKindConstants.ADD_ON_ONLY : PurchaseKindConstants.PLAN));
      }
    }
    rows.sort(Comparator.comparing(PlanTransactionResponse::getSortAt,
        Comparator.nullsLast(Comparator.reverseOrder())));
    return rows;
  }

  /**
   * History row for a transaction. {@code order} is the shop's paid order behind it, when verified.
   * Assigned rows carry no amount (no verified charge); a snapshot-less verified row shows what the
   * order charged, with the order's breakdown. A row from before terms were stored gets an
   * estimated term from its date and duration.
   */
  PlanTransactionResponse transactionRow(PlanTransaction tx, PlanPaymentOrder order, String kind) {
    boolean assigned = PurchaseKindConstants.ASSIGNED.equals(kind);
    boolean fromOrder = !assigned && tx.getItems() == null && order != null;
    PlanTransactionResponse r = planTransactionMapper.toRow(tx);
    r.setKind(kind);
    r.setStatus(order != null ? order.getStatus() : null);
    r.setAmount(assigned ? null : (fromOrder ? order.getAmount() : tx.getAmount()));
    r.setSortAt(tx.getCreatedAt());
    r.setRefundedAt(order != null && order.getRefundedAt() != null ? order.getRefundedAt() : tx.getRefundedAt());
    if (tx.getItems() == null) {
      r.setSubtotal(null);
      r.setDiscountTotal(null);
      r.setWalletCredit(null);
      if (fromOrder) {
        r.setItems(order.getItems() != null ? planTransactionMapper.toLineResponses(order.getItems()) : null);
        r.setSubtotal(order.getSubtotal());
        r.setDiscountTotal(order.getDiscountTotal());
        r.setWalletCredit(order.getWalletCredit());
      }
    }
    if (tx.getTermEndsAt() == null) {
      r.setTermStartsAt(null);
      if (tx.getCreatedAt() != null) {
        int months = tx.getDurationMonths() != null ? tx.getDurationMonths() : PlanConstants.DEFAULT_DURATION_MONTHS;
        r.setTermStartsAt(tx.getCreatedAt());
        r.setTermEndsAt(PlanUtils.plusMonths(tx.getCreatedAt(), months));
        r.setTermEstimated(true);
      }
    }
    applyRefund(r, order);
    return r;
  }

  /** History row for a paid order that has no transaction (add-on-only, or paid but not yet granted). */
  PlanTransactionResponse orderRow(PlanPaymentOrder order, String kind) {
    PlanTransactionResponse r = planTransactionMapper.toRow(order);
    r.setKind(kind);
    r.setCreatedAt(order.getPaidAt() != null ? order.getPaidAt() : order.getCreatedAt());
    r.setSortAt(r.getCreatedAt());
    applyRefund(r, order);
    return r;
  }

  /** Money returned on a refunded order: to the payment method, and wallet credit given back. */
  private static void applyRefund(PlanTransactionResponse r, PlanPaymentOrder order) {
    if (order == null) {
      return;
    }
    BigDecimal refunded = order.getRefundedAmount();
    if (refunded != null && refunded.signum() > 0) {
      r.setRefundedToPaymentMethod(refunded);
    }
    BigDecimal wallet = order.getWalletCredit();
    if (PlanPaymentConstants.STATUS_REFUNDED.equals(order.getStatus()) && wallet != null && wallet.signum() > 0) {
      r.setWalletCreditReturned(wallet);
    }
  }

  private static boolean isVerifiedPlanOrder(PlanTransaction tx, PlanPaymentOrder order) {
    return order != null && !order.isAddOnOnly() && order.getPlanId() != null
        && order.getPlanId().equals(tx.getPlanId());
  }
}
