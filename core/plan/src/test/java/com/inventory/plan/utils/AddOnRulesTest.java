package com.inventory.plan.utils;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventory.plan.domain.model.AddOnBillingType;
import com.inventory.plan.domain.model.AddOnGrantType;
import com.inventory.plan.rest.dto.request.AddOnAdminRequest;
import org.junit.jupiter.api.Test;

class AddOnRulesTest {

  @Test
  void ocrCreditsDefaultToOneTimeAndStackable() {
    AddOnAdminRequest request = new AddOnAdminRequest();

    AddOnRules.applyDefaults(request, AddOnGrantType.OCR_CREDITS);

    assertThat(request.getBillingType()).isEqualTo(AddOnBillingType.ONE_TIME);
    assertThat(request.getStackable()).isTrue();
  }

  @Test
  void aFeatureDefaultsToAnnualOnceAndOne() {
    AddOnAdminRequest request = new AddOnAdminRequest();

    AddOnRules.applyDefaults(request, AddOnGrantType.FEATURE);

    assertThat(request.getBillingType()).isEqualTo(AddOnBillingType.ANNUAL);
    assertThat(request.getStackable()).isFalse();
    assertThat(request.getGrantsQuantity()).isEqualTo(1);
  }

  @Test
  void whatTheAdminSentIsKept() {
    AddOnAdminRequest request = new AddOnAdminRequest();
    request.setBillingType(AddOnBillingType.ANNUAL);
    request.setStackable(false);

    AddOnRules.applyDefaults(request, AddOnGrantType.SEATS);

    assertThat(request.getBillingType()).isEqualTo(AddOnBillingType.ANNUAL);
    assertThat(request.getStackable()).isFalse();
  }
}
