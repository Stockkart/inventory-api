package com.inventory.plan.domain.model;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AddOnVoucherTest {

  @Test
  void legacyVoucherWithoutTargetIsAddOn() {
    AddOnVoucher voucher = new AddOnVoucher();

    assertThat(voucher.getTarget()).isNull();
    assertThat(voucher.effectiveTarget()).isEqualTo(VoucherTarget.ADD_ON);
    assertThat(voucher.isPlanVoucher()).isFalse();
  }

  @Test
  void planVoucherReportsPlanTarget() {
    AddOnVoucher voucher = new AddOnVoucher();
    voucher.setTarget(VoucherTarget.PLAN);

    assertThat(voucher.effectiveTarget()).isEqualTo(VoucherTarget.PLAN);
    assertThat(voucher.isPlanVoucher()).isTrue();
  }
}
