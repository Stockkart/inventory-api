package com.inventory.plan.service.voucher;

import com.inventory.plan.domain.model.AddOnVoucher;
import com.inventory.plan.domain.model.OrderLine;
import com.inventory.plan.domain.model.VoucherRejection;
import com.inventory.plan.domain.model.Plan;
import com.inventory.plan.domain.repository.AddOnVoucherRepository;
import com.inventory.plan.domain.repository.PlanRepository;
import com.inventory.plan.domain.repository.VoucherRedemptionRepository;
import com.inventory.plan.exception.VoucherRejectedException;
import com.inventory.plan.mapper.VoucherMapper;
import com.inventory.plan.rest.dto.response.VoucherCheckResponse;
import com.inventory.plan.utils.VoucherDiscountUtils;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Validates vouchers and works out their discount. Never consumes anything: slots are taken by
 * {@link VoucherReservationHandler} when checkout creates the order.
 */
@Service
public class VoucherService {


  @Autowired
  private AddOnVoucherRepository voucherRepository;

  @Autowired
  private VoucherRedemptionRepository redemptionRepository;

  @Autowired
  private PlanRepository planRepository;

  @Autowired
  private VoucherMapper voucherMapper;

  @Autowired
  private VoucherLookupRateLimiter lookupLimiter;

  Clock clock = Clock.systemUTC();

  public static String normalise(String code) {
    return code == null ? "" : code.trim().toUpperCase(Locale.ROOT);
  }

  /** The voucher if {@code shopId} may use it now, else a typed rejection. */
  public AddOnVoucher requireUsable(String rawCode, String shopId) {
    return requireUsable(rawCode, shopId, null);
  }

  /**
   * As {@link #requireUsable(String, String)}; unknown codes count against the shop and the user
   * ({@link VoucherLookupRateLimiter}), and too many of them answer 429.
   */
  public AddOnVoucher requireUsable(String rawCode, String shopId, String userId) {
    String code = normalise(rawCode);
    String shopKey = VoucherLookupRateLimiter.shopKey(shopId);
    String userKey = VoucherLookupRateLimiter.userKey(userId);
    lookupLimiter.checkAllowed(shopKey, userKey);
    return checkUsable(code, voucherRepository.findByCode(code).orElse(null), shopId, shopKey, userKey);
  }

  /**
   * {@link #requireUsable(String, String)} for several normalised codes with one catalogue read;
   * codes are checked in the given order and the first unusable one throws. Keyed by code, in order.
   */
  public Map<String, AddOnVoucher> requireUsableAll(Collection<String> codes, String shopId) {
    String shopKey = VoucherLookupRateLimiter.shopKey(shopId);
    String userKey = VoucherLookupRateLimiter.userKey(null);
    Map<String, AddOnVoucher> found = codes.isEmpty() ? Map.of()
        : voucherRepository.findByCodeIn(codes).stream()
            .collect(Collectors.toMap(AddOnVoucher::getCode, Function.identity(), (a, b) -> a));
    Map<String, AddOnVoucher> usable = new LinkedHashMap<>();
    for (String code : codes) {
      lookupLimiter.checkAllowed(shopKey, userKey);
      usable.put(code, checkUsable(code, found.get(code), shopId, shopKey, userKey));
    }
    return usable;
  }

  private AddOnVoucher checkUsable(String code, AddOnVoucher voucher, String shopId, String shopKey,
      String userKey) {
    if (voucher == null) {
      lookupLimiter.recordMiss(shopKey, userKey);
      throw new VoucherRejectedException(code, VoucherRejection.NOT_FOUND, "Voucher " + code + " does not exist");
    }
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
        && redemptionRepository.existsByVoucherCodeAndShopIdAndHoldsSlotTrueAndSingleUsePerShopTrue(code, shopId)) {
      throw reject(code, VoucherRejection.ALREADY_REDEEMED, "You have already used voucher " + code);
    }
    return voucher;
  }

  /** Non-consuming check for the checkout form. */
  public VoucherCheckResponse check(String rawCode, String shopId) {
    return check(rawCode, shopId, null);
  }

  /** Non-consuming check for the checkout form; unknown codes count against shop and user. */
  public VoucherCheckResponse check(String rawCode, String shopId, String userId) {
    try {
      AddOnVoucher voucher = requireUsable(rawCode, shopId, userId);
      VoucherCheckResponse response = voucherMapper.toCheckResponse(voucher, true, null);
      if (voucher.isPlanVoucher()) {
        response.setPlanName(planRepository.findByCode(voucher.getPlanCode())
            .map(Plan::getPlanName).orElse(voucher.getPlanCode()));
      }
      return response;
    } catch (VoucherRejectedException e) {
      return VoucherCheckResponse.builder().code(normalise(rawCode)).valid(false).reason(e.getReason()).build();
    }
  }

  /** Discount the voucher gives on {@code line}, never more than the line's gross amount. */
  public static BigDecimal discountFor(AddOnVoucher voucher, OrderLine line) {
    return VoucherDiscountUtils.discountFor(voucher, line);
  }

  private static VoucherRejectedException reject(String code, VoucherRejection reason, String message) {
    return new VoucherRejectedException(code, reason, message);
  }
}
