package com.inventory.plan.service.voucher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.inventory.plan.domain.model.AddOnVoucher;
import com.inventory.plan.domain.model.OrderLine;
import com.inventory.plan.domain.model.VoucherRejection;
import com.inventory.plan.domain.model.VoucherType;
import com.inventory.plan.domain.repository.AddOnVoucherRepository;
import com.inventory.plan.domain.repository.VoucherRedemptionRepository;
import com.inventory.plan.exception.VoucherRejectedException;
import com.inventory.plan.domain.model.Plan;
import com.inventory.plan.domain.model.VoucherTarget;
import com.inventory.plan.domain.repository.PlanRepository;
import com.inventory.plan.mapper.VoucherMapper;
import com.inventory.plan.mapper.VoucherMapperImpl;
import org.mockito.Spy;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class VoucherServiceTest {

  private static final Instant NOW = Instant.parse("2026-09-01T10:00:00Z");

  @Mock private AddOnVoucherRepository voucherRepository;
  @Mock private VoucherRedemptionRepository redemptionRepository;
  @Spy private VoucherMapper voucherMapper = new VoucherMapperImpl();
  @Mock private PlanRepository planRepository;

  @InjectMocks
  private VoucherService service;

  @BeforeEach
  void setUp() {
    service.clock = Clock.fixed(NOW, ZoneOffset.UTC);
  }

  @Test
  void usableVoucherPasses() {
    stored(v -> {});
    assertThat(service.requireUsable(" mkt-1 ", "shop-1").getCode()).isEqualTo("MKT-1");
  }

  @Test
  void eachFailureHasItsOwnReason() {
    when(voucherRepository.findByCode("NOPE")).thenReturn(Optional.empty());
    assertRejected("NOPE", VoucherRejection.NOT_FOUND);

    stored(v -> v.setActive(false));
    assertRejected("MKT-1", VoucherRejection.INACTIVE);

    stored(v -> v.setValidTo(NOW));
    assertRejected("MKT-1", VoucherRejection.EXPIRED);

    stored(v -> v.setValidFrom(NOW.plusSeconds(1)));
    assertRejected("MKT-1", VoucherRejection.EXPIRED);

    stored(v -> v.setIssuedToShopId("shop-2"));
    assertRejected("MKT-1", VoucherRejection.WRONG_SHOP);

    stored(v -> {
      v.setMaxRedemptions(3);
      v.setReservedCount(1);
      v.setRedemptionCount(2);
    });
    assertRejected("MKT-1", VoucherRejection.EXHAUSTED);

    stored(v -> v.setSingleUsePerShop(true));
    when(redemptionRepository.existsByVoucherCodeAndShopIdAndHoldsSlotTrue("MKT-1", "shop-1")).thenReturn(true);
    assertRejected("MKT-1", VoucherRejection.ALREADY_REDEEMED);
  }

  @Test
  void discountsNeverExceedTheLine() {
    OrderLine line = OrderLine.builder().unitPrice(new BigDecimal("500")).quantity(3).build();

    assertThat(VoucherService.discountFor(voucher(VoucherType.FREE_ADDON, null, 1), line)).isEqualByComparingTo("500");
    assertThat(VoucherService.discountFor(voucher(VoucherType.FREE_ADDON, null, 5), line)).isEqualByComparingTo("1500");
    assertThat(VoucherService.discountFor(voucher(VoucherType.PERCENT_OFF, new BigDecimal("33.333"), 1), line))
        .isEqualByComparingTo("500.00");
    assertThat(VoucherService.discountFor(voucher(VoucherType.FLAT_OFF, new BigDecimal("9000"), 1), line))
        .isEqualByComparingTo("1500");
  }

  @Test
  void checkDescribesPlanVoucher() {
    stored(v -> {
      v.setTarget(VoucherTarget.PLAN);
      v.setPlanCode("STARTER");
      v.setAddOnCode(null);
    });
    Plan plan = new Plan();
    plan.setCode("STARTER");
    plan.setPlanName("Starter");
    when(planRepository.findByCode("STARTER")).thenReturn(Optional.of(plan));

    var response = service.check("MKT-1", "shop-1");

    assertThat(response.isValid()).isTrue();
    assertThat(response.getTarget()).isEqualTo(VoucherTarget.PLAN);
    assertThat(response.getPlanCode()).isEqualTo("STARTER");
    assertThat(response.getPlanName()).isEqualTo("Starter");
    assertThat(response.getAddOnCode()).isNull();
  }

  @Test
  void checkDescribesAddOnVoucherWithoutPlanLookup() {
    stored(v -> {});

    var response = service.check("MKT-1", "shop-1");

    assertThat(response.isValid()).isTrue();
    assertThat(response.getTarget()).isEqualTo(VoucherTarget.ADD_ON);
    assertThat(response.getAddOnCode()).isEqualTo("MARKETING_MODULE");
    assertThat(response.getPlanCode()).isNull();
    assertThat(response.getPlanName()).isNull();
    org.mockito.Mockito.verifyNoInteractions(planRepository);
  }

  private void assertRejected(String code, VoucherRejection reason) {
    assertThatThrownBy(() -> service.requireUsable(code, "shop-1"))
        .isInstanceOfSatisfying(VoucherRejectedException.class, e -> {
          assertThat(e.getReason()).isEqualTo(reason);
          assertThat(e.getDetails()).containsEntry("reason", reason.name());
        });
  }

  private void stored(Consumer<AddOnVoucher> customise) {
    AddOnVoucher voucher = voucher(VoucherType.FREE_ADDON, null, 1);
    voucher.setCode("MKT-1");
    customise.accept(voucher);
    when(voucherRepository.findByCode("MKT-1")).thenReturn(Optional.of(voucher));
  }

  private static AddOnVoucher voucher(VoucherType type, BigDecimal value, int quantity) {
    return AddOnVoucher.builder().code("MKT-1").addOnCode("MARKETING_MODULE").type(type).value(value)
        .quantity(quantity).build();
  }
}
