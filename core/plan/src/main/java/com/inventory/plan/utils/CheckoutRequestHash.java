package com.inventory.plan.utils;

import com.inventory.plan.rest.dto.request.QuoteRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Canonical hash of a checkout cart (§27.6): shop, plan, duration, add-ons sorted by code with
 * quantities, voucher codes uppercased and sorted, and the wallet flag. Server-computed amounts are
 * deliberately left out, so a retry after a balance change still matches.
 */
public final class CheckoutRequestHash {

  private CheckoutRequestHash() {}

  /** {@code planKey} is the plan code, or "id:" + plan id for legacy plans without one. */
  public static String of(String shopId, String planKey, QuoteRequest cart) {
    String addOns = cart.getAddOns() == null ? "" : cart.getAddOns().stream()
        .filter(Objects::nonNull)
        .sorted(Comparator.comparing(line -> normalise(line.getCode())))
        .map(line -> normalise(line.getCode()) + "x" + (line.getQuantity() == null ? 1 : line.getQuantity()))
        .collect(Collectors.joining(","));
    String vouchers = cart.getVoucherCodes() == null ? "" : cart.getVoucherCodes().stream()
        .filter(Objects::nonNull)
        .map(CheckoutRequestHash::normalise)
        .sorted()
        .collect(Collectors.joining(","));
    String canonical = String.join("|", List.of(
        shopId,
        planKey,
        String.valueOf(cart.getDurationMonths()),
        addOns,
        vouchers,
        String.valueOf(Boolean.TRUE.equals(cart.getApplyWalletCredit()))));
    return sha256(canonical);
  }

  private static String normalise(String code) {
    return code == null ? "" : code.trim().toUpperCase(Locale.ROOT);
  }

  private static String sha256(String value) {
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }
}
