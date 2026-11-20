package com.inventory.plan.mapper;

import com.inventory.plan.domain.model.OrderLine;
import com.inventory.plan.domain.model.Plan;
import com.inventory.plan.domain.model.PlanPaymentOrder;
import com.inventory.plan.domain.model.PlanTransaction;
import com.inventory.plan.rest.dto.request.AssignPlanRequest;
import com.inventory.plan.rest.dto.response.PlanTransactionResponse;
import com.inventory.plan.rest.dto.response.PurchaseLineResponse;
import com.inventory.plan.utils.PlanUtils;
import com.inventory.plan.utils.constants.PlanPaymentConstants;
import com.inventory.plan.utils.constants.PurchaseKindConstants;
import com.inventory.plan.utils.constants.PlanConstants;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.stream.Collectors;

@Mapper(componentModel = "spring")
public interface PlanTransactionMapper {

  @Mapping(target = "id", ignore = true)
  @Mapping(target = "shopId", source = "shopId")
  @Mapping(target = "planId", source = "plan.id")
  @Mapping(target = "planName", source = "plan.planName")
  @Mapping(target = "amount", source = "plan", qualifiedByName = "planAmount")
  @Mapping(target = "durationMonths", source = "request", qualifiedByName = "durationMonths")
  @Mapping(target = "paymentMethod", source = "request", qualifiedByName = "paymentMethod")
  @Mapping(target = "paymentOrderId", source = "request.paymentOrderId")
  @Mapping(target = "provider", source = "request.provider")
  @Mapping(target = "providerPaymentId", source = "request.providerPaymentId")
  @Mapping(target = "providerOrderId", source = "request.providerOrderId")
  @Mapping(target = "createdAt", expression = "java(java.time.Instant.now())")
  PlanTransaction toTransaction(String shopId, Plan plan, AssignPlanRequest request);

  /** Transaction for a paid order, carrying the order's pricing snapshot and the granted term. */
  default PlanTransaction toTransaction(PlanPaymentOrder order, Plan plan, Instant termStartsAt, Instant termEndsAt) {
    PlanTransaction tx = new PlanTransaction();
    tx.setShopId(order.getShopId());
    tx.setPlanId(plan.getId());
    tx.setPlanCode(plan.getCode());
    tx.setPlanName(plan.getPlanName());
    tx.setAmount(order.getAmount());
    tx.setDurationMonths(order.getDurationMonths());
    tx.setPaymentMethod(order.getPaymentMethod() != null ? order.getPaymentMethod() : PlanConstants.DEFAULT_PAYMENT_METHOD);
    tx.setPaymentOrderId(order.getId());
    tx.setProvider(order.getProvider());
    tx.setProviderPaymentId(order.getProviderPaymentId());
    tx.setProviderOrderId(order.getProviderOrderId());
    tx.setItems(order.getItems());
    tx.setSubtotal(order.getSubtotal());
    tx.setDiscountTotal(order.getDiscountTotal());
    tx.setWalletCredit(order.getWalletCredit());
    tx.setTermStartsAt(termStartsAt);
    tx.setTermEndsAt(termEndsAt);
    tx.setCreatedAt(termStartsAt);
    return tx;
  }

  PurchaseLineResponse toLineResponse(OrderLine line);

  /**
   * History row for a transaction. {@code order} is the shop's paid order behind it, when verified.
   * Assigned rows carry no amount (no verified charge); a snapshot-less verified row shows what the
   * order charged, with the order's breakdown.
   */
  default PlanTransactionResponse toResponse(PlanTransaction tx, PlanPaymentOrder order, String kind) {
    boolean assigned = PurchaseKindConstants.ASSIGNED.equals(kind);
    boolean fromOrder = !assigned && tx.getItems() == null && order != null;
    BigDecimal amount = assigned ? null : (fromOrder ? order.getAmount() : tx.getAmount());
    PlanTransactionResponse r = new PlanTransactionResponse();
    r.setId(tx.getId());
    r.setShopId(tx.getShopId());
    r.setPlanId(tx.getPlanId());
    r.setPlanName(tx.getPlanName());
    r.setPlanCode(tx.getPlanCode());
    r.setKind(kind);
    r.setStatus(order != null ? order.getStatus() : null);
    r.setAmount(amount);
    r.setDurationMonths(tx.getDurationMonths());
    r.setPaymentMethod(tx.getPaymentMethod());
    r.setProvider(tx.getProvider());
    r.setProviderPaymentId(tx.getProviderPaymentId());
    r.setCreatedAt(tx.getCreatedAt());
    r.setSortAt(tx.getCreatedAt());
    r.setPaymentOrderId(tx.getPaymentOrderId());
    r.setRefundedAt(order != null && order.getRefundedAt() != null ? order.getRefundedAt() : tx.getRefundedAt());
    if (tx.getItems() != null) {
      r.setItems(tx.getItems().stream().map(this::toLineResponse).collect(Collectors.toList()));
      r.setSubtotal(tx.getSubtotal());
      r.setDiscountTotal(tx.getDiscountTotal());
      r.setWalletCredit(tx.getWalletCredit());
    } else if (fromOrder) {
      if (order.getItems() != null) {
        r.setItems(order.getItems().stream().map(this::toLineResponse).collect(Collectors.toList()));
      }
      r.setSubtotal(order.getSubtotal());
      r.setDiscountTotal(order.getDiscountTotal());
      r.setWalletCredit(order.getWalletCredit());
    }
    if (tx.getTermEndsAt() != null) {
      r.setTermStartsAt(tx.getTermStartsAt());
      r.setTermEndsAt(tx.getTermEndsAt());
    } else if (tx.getCreatedAt() != null) {
      int months = tx.getDurationMonths() != null ? tx.getDurationMonths() : PlanConstants.DEFAULT_DURATION_MONTHS;
      r.setTermStartsAt(tx.getCreatedAt());
      r.setTermEndsAt(PlanUtils.plusMonths(tx.getCreatedAt(), months));
      r.setTermEstimated(true);
    }
    applyRefund(r, order);
    return r;
  }

  /** History row for a paid order that has no transaction (add-on-only, or paid but not yet granted). */
  default PlanTransactionResponse toResponse(PlanPaymentOrder order, String kind) {
    PlanTransactionResponse r = new PlanTransactionResponse();
    r.setId(order.getId());
    r.setShopId(order.getShopId());
    r.setPlanId(order.getPlanId());
    r.setPlanName(order.getPlanName());
    r.setPlanCode(order.getPlanCode());
    r.setKind(kind);
    r.setStatus(order.getStatus());
    r.setAmount(order.getAmount());
    r.setDurationMonths(order.getDurationMonths());
    r.setPaymentMethod(order.getPaymentMethod());
    r.setProvider(order.getProvider());
    r.setProviderPaymentId(order.getProviderPaymentId());
    r.setCreatedAt(order.getPaidAt() != null ? order.getPaidAt() : order.getCreatedAt());
    r.setSortAt(r.getCreatedAt());
    r.setPaymentOrderId(order.getId());
    r.setRefundedAt(order.getRefundedAt());
    if (order.getItems() != null) {
      r.setItems(order.getItems().stream().map(this::toLineResponse).collect(Collectors.toList()));
    }
    r.setSubtotal(order.getSubtotal());
    r.setDiscountTotal(order.getDiscountTotal());
    r.setWalletCredit(order.getWalletCredit());
    r.setTermEndsAt(order.getTermEndsAt());
    applyRefund(r, order);
    return r;
  }

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

  @Named("planAmount")
  default BigDecimal planAmount(Plan plan) {
    return plan.getArcPrice() != null ? plan.getArcPrice() : plan.getPrice();
  }

  @Named("durationMonths")
  default int durationMonths(AssignPlanRequest request) {
    return request.getDurationMonths() != null ? request.getDurationMonths() : PlanConstants.DEFAULT_DURATION_MONTHS;
  }

  @Named("paymentMethod")
  default String paymentMethod(AssignPlanRequest request) {
    return request.getPaymentMethod() != null ? request.getPaymentMethod() : PlanConstants.DEFAULT_PAYMENT_METHOD;
  }
}
