package com.inventory.plan.validation;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.inventory.common.exception.ValidationException;
import com.inventory.plan.domain.model.VoucherType;
import com.inventory.plan.rest.dto.request.VoucherGenerateRequest;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class VoucherAdminValidatorTest {

  private final VoucherAdminValidator validator = new VoucherAdminValidator();

  @Test
  void acceptsSingleAndBulkVouchers() {
    assertThatCode(() -> validator.validateGenerate(base().code("MKT-9F3K2P").build())).doesNotThrowAnyException();
    assertThatCode(() -> validator.validateGenerate(base().count(200).prefix("DIWALI").build())).doesNotThrowAnyException();
    assertThatCode(() -> validator.validateGenerate(base().type(VoucherType.PERCENT_OFF).value(new BigDecimal("25")).build()))
        .doesNotThrowAnyException();
  }

  @Test
  void rejectsBadVouchers() {
    assertInvalid(base().code("MKT-1").count(2).build());
    assertInvalid(base().count(501).build());
    assertInvalid(base().code("bad code!").build());
    assertInvalid(base().prefix("toolongprefix").build());
    assertInvalid(base().addOnCode(null).build());
    assertInvalid(base().value(BigDecimal.ONE).build());
    assertInvalid(base().type(VoucherType.PERCENT_OFF).value(new BigDecimal("101")).build());
    assertInvalid(base().type(VoucherType.FLAT_OFF).build());
    assertInvalid(base().maxRedemptions(0).build());
    assertInvalid(base().quantity(0).build());
    assertInvalid(base().validFrom(Instant.parse("2026-10-01T00:00:00Z")).validTo(Instant.parse("2026-09-01T00:00:00Z")).build());
  }

  private void assertInvalid(VoucherGenerateRequest request) {
    assertThatThrownBy(() -> validator.validateGenerate(request)).isInstanceOf(ValidationException.class);
  }

  private static VoucherGenerateRequest.VoucherGenerateRequestBuilder base() {
    return VoucherGenerateRequest.builder().addOnCode("MARKETING_MODULE").type(VoucherType.FREE_ADDON);
  }
}
