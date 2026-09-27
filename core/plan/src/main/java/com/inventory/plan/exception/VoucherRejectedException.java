package com.inventory.plan.exception;

import com.inventory.common.constants.ErrorCode;
import com.inventory.common.exception.DetailedException;
import com.inventory.plan.domain.model.VoucherRejection;
import java.util.Map;
import lombok.Getter;

/** A voucher cannot be used; {@code details.reason} is the typed {@link VoucherRejection}. */
@Getter
public class VoucherRejectedException extends DetailedException {

  private final VoucherRejection reason;

  public VoucherRejectedException(String voucherCode, VoucherRejection reason, String message) {
    super(ErrorCode.VOUCHER_REJECTED, message, Map.of("voucherCode", voucherCode, "reason", reason.name()));
    this.reason = reason;
  }
}
