package com.inventory.plan.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.inventory.plan.domain.model.OrderLine;
import com.inventory.plan.domain.model.PlanPaymentOrder;
import com.inventory.plan.domain.model.PlanTransaction;
import com.inventory.plan.domain.repository.PlanPaymentOrderRepository;
import com.inventory.plan.domain.repository.PlanTransactionRepository;
import com.inventory.plan.mapper.PlanTransactionMapper;
import com.inventory.plan.mapper.PlanTransactionMapperImpl;
import com.inventory.plan.rest.dto.response.PlanTransactionResponse;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Sort;

@ExtendWith(MockitoExtension.class)
class PurchaseHistoryServiceTest {

  private static final String SHOP = "shop-1";
  private static final Instant T1 = Instant.parse("2026-09-01T10:00:00Z");
  private static final Instant T2 = Instant.parse("2026-10-01T10:00:00Z");
  private static final Instant T3 = Instant.parse("2026-11-01T10:00:00Z");

  @Mock private PlanTransactionRepository transactionRepository;
  @Mock private PlanPaymentOrderRepository orderRepository;
  @Spy private PlanTransactionMapper planTransactionMapper = new PlanTransactionMapperImpl();

  @InjectMocks private PurchaseHistoryService service;

  private void given(List<PlanTransaction> txs, List<PlanPaymentOrder> orders) {
    when(transactionRepository.findByShopId(eq(SHOP), any(Sort.class))).thenReturn(txs);
    when(orderRepository.findByShopIdAndStatusIn(eq(SHOP), any())).thenReturn(orders);
  }

  private static PlanTransaction tx(String id, String planId, Instant createdAt, String orderId) {
    PlanTransaction t = new PlanTransaction();
    t.setId(id);
    t.setShopId(SHOP);
    t.setPlanId(planId);
    t.setPlanName("Pro");
    t.setAmount(new BigDecimal("5999.00"));
    t.setDurationMonths(1);
    t.setCreatedAt(createdAt);
    t.setPaymentOrderId(orderId);
    return t;
  }

  private static PlanPaymentOrder order(String id, String planId, String status, boolean addOnOnly, String amount) {
    PlanPaymentOrder o = new PlanPaymentOrder();
    o.setId(id);
    o.setShopId(SHOP);
    o.setPlanId(planId);
    o.setStatus(status);
    o.setAddOnOnly(addOnOnly);
    o.setAmount(new BigDecimal(amount));
    o.setCreatedAt(T2);
    o.setPaidAt(T2);
    return o;
  }

  private static OrderLine line() {
    return OrderLine.builder().type("PLAN").code("PRO").name("Pro").quantity(1)
        .unitPrice(new BigDecimal("4999.00")).discount(BigDecimal.ZERO).lineTotal(new BigDecimal("4999.00"))
        .voucherCode("V1").walletCredit(BigDecimal.ZERO).build();
  }

  @Test
  void paidPlanShowsFullBreakdown() {
    PlanTransaction t = tx("t1", "p1", T1, "o1");
    t.setItems(List.of(line()));
    t.setSubtotal(new BigDecimal("4999.00"));
    t.setDiscountTotal(new BigDecimal("100.00"));
    t.setWalletCredit(new BigDecimal("50.00"));
    t.setTermStartsAt(T1);
    t.setTermEndsAt(T2);
    t.setAmount(new BigDecimal("4849.00"));
    given(List.of(t), List.of(order("o1", "p1", "FULFILLED", false, "4849.00")));

    List<PlanTransactionResponse> rows = service.forShop(SHOP);

    assertThat(rows).hasSize(1);
    PlanTransactionResponse r = rows.get(0);
    assertThat(r.getKind()).isEqualTo("PLAN");
    assertThat(r.getStatus()).isEqualTo("FULFILLED");
    assertThat(r.getItems()).hasSize(1);
    assertThat(r.getItems().get(0).getVoucherCode()).isEqualTo("V1");
    assertThat(r.getItems().get(0).getLineTotal()).isEqualByComparingTo("4999.00");
    assertThat(r.getAmount()).isEqualByComparingTo("4849.00");
    assertThat(r.getSubtotal()).isEqualByComparingTo("4999.00");
    assertThat(r.getDiscountTotal()).isEqualByComparingTo("100.00");
    assertThat(r.getWalletCredit()).isEqualByComparingTo("50.00");
    assertThat(r.getTermStartsAt()).isEqualTo(T1);
    assertThat(r.getTermEndsAt()).isEqualTo(T2);
    assertThat(r.isTermEstimated()).isFalse();
  }

  @Test
  void addOnOnlyOrderAppears() {
    PlanPaymentOrder o = order("o1", "p1", "FULFILLED", true, "300.00");
    Instant end = Instant.parse("2027-01-01T00:00:00Z");
    o.setTermEndsAt(end);
    given(List.of(), List.of(o));

    List<PlanTransactionResponse> rows = service.forShop(SHOP);

    assertThat(rows).hasSize(1);
    assertThat(rows.get(0).getKind()).isEqualTo("ADD_ON_ONLY");
    assertThat(rows.get(0).getTermEndsAt()).isEqualTo(end);
    assertThat(rows.get(0).getAmount()).isEqualByComparingTo("300.00");
    assertThat(rows.get(0).getPaymentOrderId()).isEqualTo("o1");
  }

  @Test
  void paidButUngrantedPlanOrderAppears() {
    given(List.of(), List.of(order("o1", "p1", "FULFILMENT_FAILED", false, "4999.00")));

    PlanTransactionResponse r = service.forShop(SHOP).get(0);

    assertThat(r.getKind()).isEqualTo("PLAN");
    assertThat(r.getStatus()).isEqualTo("FULFILMENT_FAILED");
    assertThat(r.getTermStartsAt()).isNull();
    assertThat(r.getTermEndsAt()).isNull();
  }

  @Test
  void assignRowWithFakePaymentIdIsAssigned() {
    PlanTransaction t = tx("t1", "p1", T1, null);
    t.setProviderPaymentId("pay_fake");
    given(List.of(t), List.of());

    PlanTransactionResponse r = service.forShop(SHOP).get(0);

    assertThat(r.getKind()).isEqualTo("ASSIGNED");
    assertThat(r.getAmount()).isNull();
    assertThat(r.isTermEstimated()).isTrue();
    assertThat(r.getTermEndsAt()).isEqualTo(Instant.parse("2026-10-01T10:00:00Z"));
  }

  @Test
  void assignRowWithRealOrderIsPaid() {
    PlanTransaction t = tx("t1", "p1", T1, "o1");
    given(List.of(t), List.of(order("o1", "p1", "FULFILLED", false, "4999.00")));

    List<PlanTransactionResponse> rows = service.forShop(SHOP);

    assertThat(rows).hasSize(1);
    assertThat(rows.get(0).getKind()).isEqualTo("PLAN");
    assertThat(rows.get(0).getAmount()).isEqualByComparingTo("4999.00");
  }

  @Test
  void assignRowPointingAtAddOnOrderIsAssigned() {
    PlanTransaction t = tx("t1", "p1", T1, "o1");
    given(List.of(t), List.of(order("o1", "p1", "FULFILLED", true, "300.00")));

    List<PlanTransactionResponse> rows = service.forShop(SHOP);

    assertThat(rows).hasSize(2);
    assertThat(rows).filteredOn(r -> r.getKind().equals("ASSIGNED")).singleElement()
        .satisfies(r -> assertThat(r.getAmount()).isNull());
    assertThat(rows).filteredOn(r -> r.getKind().equals("ADD_ON_ONLY")).hasSize(1);
  }

  @Test
  void assignRowPointingAtOtherPlanOrderIsAssigned() {
    PlanTransaction t = tx("t1", "p1", T1, "o1");
    given(List.of(t), List.of(order("o1", "p2", "FULFILLED", false, "9999.00")));

    List<PlanTransactionResponse> rows = service.forShop(SHOP);

    assertThat(rows).hasSize(2);
    assertThat(rows).filteredOn(r -> r.getKind().equals("ASSIGNED")).singleElement()
        .satisfies(r -> assertThat(r.getAmount()).isNull());
    assertThat(rows).filteredOn(r -> r.getKind().equals("PLAN")).singleElement()
        .satisfies(r -> assertThat(r.getAmount()).isEqualByComparingTo("9999.00"));
  }

  @Test
  void partialRefundShownWhileFulfilled() {
    PlanPaymentOrder o = order("o1", "p1", "FULFILLED", false, "4999.00");
    o.setRefundedAmount(new BigDecimal("500"));
    o.setWalletCredit(new BigDecimal("100"));
    given(List.of(), List.of(o));

    PlanTransactionResponse r = service.forShop(SHOP).get(0);

    assertThat(r.getRefundedToPaymentMethod()).isEqualByComparingTo("500");
    assertThat(r.getWalletCreditReturned()).isNull();
  }

  @Test
  void fullRefundShowsWalletReturned() {
    PlanPaymentOrder o = order("o1", "p1", "REFUNDED", false, "4999.00");
    o.setRefundedAmount(new BigDecimal("4999"));
    o.setWalletCredit(new BigDecimal("2000"));
    o.setRefundedAt(T3);
    given(List.of(), List.of(o));

    PlanTransactionResponse r = service.forShop(SHOP).get(0);

    assertThat(r.getRefundedToPaymentMethod()).isEqualByComparingTo("4999");
    assertThat(r.getWalletCreditReturned()).isEqualByComparingTo("2000");
    assertThat(r.getRefundedAt()).isEqualTo(T3);
  }

  @Test
  void sortedNewestFirst() {
    PlanTransaction old = tx("t1", "p1", T1, null);
    PlanTransaction recent = tx("t2", "p1", T3, null);
    given(List.of(old, recent), List.of(order("o1", "p1", "PAID", true, "300.00")));

    List<PlanTransactionResponse> rows = service.forShop(SHOP);

    assertThat(rows).extracting(PlanTransactionResponse::getSortAt).containsExactly(T3, T2, T1);
  }

  @Test
  void noDuplicateForGrantedOrder() {
    PlanTransaction t = tx("t1", "p1", T1, "o1");
    t.setItems(List.of(line()));
    given(List.of(t), List.of(order("o1", "p1", "FULFILLED", false, "4999.00")));

    assertThat(service.forShop(SHOP)).hasSize(1);
  }

  @Test
  void usesOneQueryPerSource() {
    given(List.of(), List.of());

    service.forShop(SHOP);

    verify(transactionRepository).findByShopId(eq(SHOP), any(Sort.class));
    verify(orderRepository).findByShopIdAndStatusIn(eq(SHOP), any());
    verifyNoMoreInteractions(transactionRepository, orderRepository);
  }
}
