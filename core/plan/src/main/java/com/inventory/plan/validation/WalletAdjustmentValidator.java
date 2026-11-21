package com.inventory.plan.validation;

import com.inventory.common.exception.ValidationException;
import com.inventory.plan.rest.dto.request.WalletAdjustmentRequest;
import java.math.BigDecimal;
import java.math.RoundingMode;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** Operator wallet adjustments ({@code /api/v1/admin/wallets/{shopId}/adjustments}). */
@Component
public class WalletAdjustmentValidator {

  /**
   * Largest single adjustment either way, in rupees. A referral reward is at most 10% of a plan
   * price, so a correction above this is almost certainly a typo; bigger moves take several
   * adjustments, each with its own reason.
   */
  public static final BigDecimal MAX_ADJUSTMENT = new BigDecimal("10000.00");

  /** Checks an adjustment and returns its amount as stored (2 decimals). */
  public BigDecimal validateAdjustment(WalletAdjustmentRequest request) {
    if (request == null) {
      throw new ValidationException("Request cannot be null");
    }
    if (!StringUtils.hasText(request.getReason())) {
      throw new ValidationException("A reason is required");
    }
    return validateAmount(request.getAmount());
  }

  /** Checks the amount of an adjustment preview (no reason needed) and returns it as stored. */
  public BigDecimal validatePreview(WalletAdjustmentRequest request) {
    if (request == null) {
      throw new ValidationException("Request cannot be null");
    }
    return validateAmount(request.getAmount());
  }

  private static BigDecimal validateAmount(BigDecimal amount) {
    if (amount == null) {
      throw new ValidationException("Adjustment amount is required");
    }
    // checked after rounding to paise, as stored: 0.001 would otherwise reach the wallet as 0
    BigDecimal scaled = amount.setScale(2, RoundingMode.HALF_UP);
    if (scaled.signum() == 0) {
      throw new ValidationException("Adjustment amount must be at least 0.01 either way");
    }
    if (scaled.abs().compareTo(MAX_ADJUSTMENT) > 0) {
      throw new ValidationException("A single adjustment can be at most " + MAX_ADJUSTMENT.toPlainString());
    }
    return scaled;
  }
}
