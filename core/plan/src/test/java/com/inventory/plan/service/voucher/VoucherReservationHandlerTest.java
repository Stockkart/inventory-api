package com.inventory.plan.service.voucher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.inventory.plan.domain.model.AddOnVoucher;
import com.inventory.plan.domain.model.OrderLine;
import com.inventory.plan.domain.model.PlanPaymentOrder;
import com.inventory.plan.domain.model.VoucherRedemption;
import com.inventory.plan.domain.model.VoucherRedemptionStatus;
import com.inventory.plan.domain.model.VoucherRejection;
import com.inventory.plan.domain.repository.AddOnVoucherRepository;
import com.inventory.plan.domain.repository.VoucherRedemptionRepository;
import com.inventory.plan.exception.VoucherRejectedException;
import com.mongodb.client.result.UpdateResult;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

@ExtendWith(MockitoExtension.class)
class VoucherReservationHandlerTest {

  @Mock private MongoTemplate mongoTemplate;
  @Mock private AddOnVoucherRepository voucherRepository;
  @Mock private VoucherRedemptionRepository redemptionRepository;

  @InjectMocks
  private VoucherReservationHandler handler;

  private final AddOnVoucher voucher = AddOnVoucher.builder().id("v-1").code("MKT-1").maxRedemptions(1).build();

  @Test
  void reserveInsertsAReservationAndTakesASlot() {
    when(voucherRepository.findByCode("MKT-1")).thenReturn(Optional.of(voucher));
    when(redemptionRepository.findByVoucherIdAndOrderId("v-1", "order-1")).thenReturn(Optional.empty());
    when(mongoTemplate.insert(any(VoucherRedemption.class))).thenAnswer(inv -> inv.getArgument(0));
    when(mongoTemplate.updateFirst(any(Query.class), any(Update.class), eq(AddOnVoucher.class))).thenReturn(updated(1));

    handler.reserve(order(), null);

    ArgumentCaptor<VoucherRedemption> row = ArgumentCaptor.forClass(VoucherRedemption.class);
    verify(mongoTemplate).insert(row.capture());
    assertThat(row.getValue().getStatus()).isEqualTo(VoucherRedemptionStatus.RESERVED);
    assertThat(row.getValue().getHoldsSlot()).isTrue();
    ArgumentCaptor<Query> capacity = ArgumentCaptor.forClass(Query.class);
    verify(mongoTemplate).updateFirst(capacity.capture(), any(Update.class), eq(AddOnVoucher.class));
    assertThat(capacity.getValue().getQueryObject().toString()).contains("$expr");
  }

  @Test
  void aFullVoucherRejectsCheckoutAndFreesTheRow() {
    when(voucherRepository.findByCode("MKT-1")).thenReturn(Optional.of(voucher));
    when(redemptionRepository.findByVoucherIdAndOrderId("v-1", "order-1")).thenReturn(Optional.empty());
    when(mongoTemplate.insert(any(VoucherRedemption.class))).thenAnswer(inv -> {
      VoucherRedemption r = inv.getArgument(0);
      r.setId("r-1");
      return r;
    });
    when(mongoTemplate.updateFirst(any(Query.class), any(Update.class), eq(AddOnVoucher.class))).thenReturn(updated(0));
    when(mongoTemplate.updateFirst(any(Query.class), any(Update.class), eq(VoucherRedemption.class))).thenReturn(updated(1));

    assertThatThrownBy(() -> handler.reserve(order(), null))
        .isInstanceOfSatisfying(VoucherRejectedException.class,
            e -> assertThat(e.getReason()).isEqualTo(VoucherRejection.EXHAUSTED));
    verify(mongoTemplate).updateFirst(any(Query.class), any(Update.class), eq(VoucherRedemption.class));
  }

  @Test
  void aShopReusingASingleUseVoucherIsRejected() {
    when(voucherRepository.findByCode("MKT-1")).thenReturn(Optional.of(voucher));
    when(redemptionRepository.findByVoucherIdAndOrderId("v-1", "order-1")).thenReturn(Optional.empty());
    when(mongoTemplate.insert(any(VoucherRedemption.class))).thenThrow(new DuplicateKeyException("dup"));

    assertThatThrownBy(() -> handler.reserve(order(), null))
        .isInstanceOfSatisfying(VoucherRejectedException.class,
            e -> assertThat(e.getReason()).isEqualTo(VoucherRejection.ALREADY_REDEEMED));
    verify(mongoTemplate, never()).updateFirst(any(Query.class), any(Update.class), eq(AddOnVoucher.class));
  }

  @Test
  void reservingTwiceForTheSameOrderIsANoOp() {
    when(voucherRepository.findByCode("MKT-1")).thenReturn(Optional.of(voucher));
    when(redemptionRepository.findByVoucherIdAndOrderId("v-1", "order-1")).thenReturn(Optional.of(
        VoucherRedemption.builder().id("r-1").status(VoucherRedemptionStatus.RESERVED).build()));

    handler.reserve(order(), null);

    verify(mongoTemplate, never()).insert(any(VoucherRedemption.class));
  }

  @Test
  void releaseGivesTheSlotBackOnlyOnce() {
    when(redemptionRepository.findByOrderId("order-1")).thenReturn(List.of(
        VoucherRedemption.builder().id("r-1").voucherId("v-1").status(VoucherRedemptionStatus.RESERVED).build()));
    when(mongoTemplate.updateFirst(any(Query.class), any(Update.class), eq(VoucherRedemption.class)))
        .thenReturn(updated(1), updated(0));

    handler.release(order());
    handler.release(order());

    verify(mongoTemplate, times(1)).updateFirst(any(Query.class), any(Update.class), eq(AddOnVoucher.class));
  }

  @Test
  void redeemMovesTheSlotFromReservedToUsed() {
    when(redemptionRepository.findByOrderId("order-1")).thenReturn(List.of(
        VoucherRedemption.builder().id("r-1").voucherId("v-1").status(VoucherRedemptionStatus.RESERVED).build(),
        VoucherRedemption.builder().id("r-2").voucherId("v-2").status(VoucherRedemptionStatus.REDEEMED).build()));
    when(mongoTemplate.findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class),
        eq(VoucherRedemption.class))).thenReturn(new VoucherRedemption());

    handler.redeem(order());

    ArgumentCaptor<Update> counters = ArgumentCaptor.forClass(Update.class);
    verify(mongoTemplate, times(1)).updateFirst(any(Query.class), counters.capture(), eq(AddOnVoucher.class));
    assertThat(counters.getValue().getUpdateObject().toString()).contains("reservedCount=-1").contains("redemptionCount=1");
  }

  private static PlanPaymentOrder order() {
    PlanPaymentOrder order = new PlanPaymentOrder();
    order.setId("order-1");
    order.setShopId("shop-1");
    order.setItems(List.of(
        OrderLine.builder().type("PLAN").code("PROFESSIONAL").quantity(1).build(),
        OrderLine.builder().type("ADDON").code("MARKETING_MODULE").quantity(1)
            .discount(new BigDecimal("1999")).voucherCode("MKT-1").build()));
    return order;
  }

  private static UpdateResult updated(long count) {
    return UpdateResult.acknowledged(count, count, null);
  }
}
