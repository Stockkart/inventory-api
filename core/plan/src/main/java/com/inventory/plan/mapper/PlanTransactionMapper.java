package com.inventory.plan.mapper;

import com.inventory.plan.domain.model.OrderLine;
import com.inventory.plan.domain.model.Plan;
import com.inventory.plan.domain.model.PlanPaymentOrder;
import com.inventory.plan.domain.model.PlanTransaction;
import com.inventory.plan.rest.dto.response.PlanTransactionResponse;
import com.inventory.plan.rest.dto.response.PurchaseLineResponse;
import com.inventory.plan.utils.constants.PlanConstants;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

@Mapper(componentModel = "spring")
public interface PlanTransactionMapper {

  /**
   * Transaction for a paid order, carrying the order's pricing snapshot and the granted term.
   * {@code createdAt} is when the grant happened; a renewal's term can start later.
   */
  default PlanTransaction toTransaction(PlanPaymentOrder order, Plan plan, Instant termStartsAt, Instant termEndsAt,
      Instant now) {
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
    tx.setCreatedAt(now);
    return tx;
  }

  PurchaseLineResponse toLineResponse(OrderLine line);

  List<PurchaseLineResponse> toLineResponses(List<OrderLine> lines);

  /** The transaction's own fields as a history row; PurchaseHistoryService decides the rest. */
  @Mapping(target = "kind", ignore = true)
  @Mapping(target = "status", ignore = true)
  @Mapping(target = "termEstimated", ignore = true)
  @Mapping(target = "refundedToPaymentMethod", ignore = true)
  @Mapping(target = "walletCreditReturned", ignore = true)
  @Mapping(target = "sortAt", ignore = true)
  PlanTransactionResponse toRow(PlanTransaction tx);

  /** A paid order's own fields as a history row (no transaction behind it). */
  @Mapping(target = "paymentOrderId", source = "id")
  @Mapping(target = "kind", ignore = true)
  @Mapping(target = "termStartsAt", ignore = true)
  @Mapping(target = "termEstimated", ignore = true)
  @Mapping(target = "refundedToPaymentMethod", ignore = true)
  @Mapping(target = "walletCreditReturned", ignore = true)
  @Mapping(target = "sortAt", ignore = true)
  PlanTransactionResponse toRow(PlanPaymentOrder order);

  /** The transaction of an admin plan grant: no order, no payment, amount zero. */
  default PlanTransaction toAdminGrantTransaction(String shopId, Plan plan, int durationMonths,
      Instant termStartsAt, Instant termEndsAt, Instant now) {
    PlanTransaction tx = new PlanTransaction();
    tx.setShopId(shopId);
    tx.setPlanId(plan.getId());
    tx.setPlanCode(plan.getCode());
    tx.setPlanName(plan.getPlanName());
    tx.setAmount(BigDecimal.ZERO);
    tx.setDurationMonths(durationMonths);
    tx.setPaymentMethod(PlanConstants.ADMIN_GRANT_PAYMENT_METHOD);
    tx.setTermStartsAt(termStartsAt);
    tx.setTermEndsAt(termEndsAt);
    tx.setCreatedAt(now);
    return tx;
  }

}
