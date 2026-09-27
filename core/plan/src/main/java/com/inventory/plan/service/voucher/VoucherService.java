package com.inventory.plan.service.voucher;

import com.inventory.plan.domain.model.AddOnVoucher;
import com.inventory.plan.domain.model.OrderLine;
import com.inventory.plan.domain.model.VoucherRejection;
import com.inventory.plan.domain.repository.AddOnVoucherRepository;
import com.inventory.plan.domain.repository.VoucherRedemptionRepository;
import com.inventory.plan.exception.VoucherRejectedException;
import com.inventory.plan.mapper.VoucherMapper;
import com.inventory.plan.rest.dto.response.VoucherCheckResponse;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Validates vouchers and works out their discount. Never consumes anything: slots are taken by
 * {@link VoucherReservationHandler} when checkout creates the order.
 */
@Service
public class VoucherService {

  private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

  @Autowired
  private AddOnVoucherRepository voucherRepository;

  @Autowired
  private VoucherRedemptionRepository redemptionRepository;

  @Autowired
  private VoucherMapper voucherMapper;

  Clock clock = Clock.systemUTC();

  public static String normalise(String code) {
    return code == null ? "" : code.trim().toUpperCase(Locale.ROOT);
  }

  /** The voucher if {@code shopId} may use it now, else a typed rejection. */
  public AddOnVoucher requireUsable(String rawCode, String shopId) {
    String code = normalise(rawCode);
    AddOnVoucher voucher = voucherRepository.findByCode(code)
        .orElseThrow(() -> new VoucherRejectedException(code, VoucherRejection.NOT_FOUND, "Voucher " + code + " does not exist"));
    Instant now = clock.instant();
    if (!voucher.isActive()) {
      throw reject(code, VoucherRejection.INACTIVE, "Voucher " + code + " is no longer active");
    }
    if ((voucher.getValidFrom() != null && now.isBefore(voucher.getValidFrom()))
        || (voucher.getValidTo() != null && !now.isBefore(voucher.getValidTo()))) {
      throw reject(code, VoucherRejection.EXPIRED, "Voucher " + code + " is not valid right now");
    }
    if (voucher.getIssuedToShopId() != null && !voucher.getIssuedToShopId().equals(shopId)) {
      throw reject(code, VoucherRejection.WRONG_SHOP, "Voucher " + code + " belongs to another shop");
    }
    if (voucher.getMaxRedemptions() != null
        && voucher.getReservedCount() + voucher.getRedemptionCount() >= voucher.getMaxRedemptions()) {
      throw reject(code, VoucherRejection.EXHAUSTED, "Voucher " + code + " has been fully used");
    }
    if (voucher.isSingleUsePerShop() && StringUtils.hasText(shopId)
        && redemptionRepository.existsByVoucherCodeAndShopIdAndHoldsSlotTrue(code, shopId)) {
      throw reject(code, VoucherRejection.ALREADY_REDEEMED, "You have already used voucher " + code);
    }
    return voucher;
  }

  /** Non-consuming check for the checkout form. */
  public VoucherCheckResponse check(String rawCode, String shopId) {
    try {
      return voucherMapper.toCheckResponse(requireUsable(rawCode, shopId), true, null);
    } catch (VoucherRejectedException e) {
      return VoucherCheckResponse.builder().code(normalise(rawCode)).valid(false).reason(e.getReason()).build();
    }
  }

  /** Discount the voucher gives on {@code line}, never more than the line's gross amount. */
  public static BigDecimal discountFor(AddOnVoucher voucher, OrderLine line) {
    BigDecimal gross = line.getUnitPrice().multiply(BigDecimal.valueOf(line.getQuantity()));
    BigDecimal discount = switch (voucher.getType()) {
      case FREE_ADDON -> line.getUnitPrice().multiply(BigDecimal.valueOf(
          Math.min(line.getQuantity(), Math.max(1, voucher.getQuantity()))));
      case PERCENT_OFF -> gross.multiply(voucher.getValue()).divide(HUNDRED, 2, RoundingMode.HALF_UP);
      case FLAT_OFF -> voucher.getValue();
    };
    return discount.min(gross).max(BigDecimal.ZERO);
  }

  private static VoucherRejectedException reject(String code, VoucherRejection reason, String message) {
    return new VoucherRejectedException(code, reason, message);
  }
}
