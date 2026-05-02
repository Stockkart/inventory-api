package com.inventory.accounting.service;

import com.inventory.accounting.domain.model.AccountType;
import com.inventory.accounting.domain.model.GlAccount;
import com.inventory.accounting.domain.repository.GlAccountRepository;
import com.inventory.common.exception.ValidationException;
import java.time.Instant;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * One liability GL nominal per vendor (code {@code VEN-&lt;sanitizedVendorId&gt;}), used as the credit
 * line on purchase journals instead of a single generic accounts-payable account.
 */
@Service
@RequiredArgsConstructor
public class VendorPayableNominalService {

  /** Code prefix for auto-created vendor payables ({@link #vendorPayableCode}). */
  public static final String VENDOR_PAYABLE_CODE_PREFIX = "VEN-";

  private final GlAccountRepository glAccountRepository;
  private final GlBootstrapService glBootstrapService;

  @Transactional
  public GlAccount resolveOrCreateVendorPayable(
      String shopId, String vendorId, String vendorDisplayName) {

    if (!StringUtils.hasText(shopId)) {
      throw new ValidationException("shopId is required");
    }
    if (!StringUtils.hasText(vendorId)) {
      throw new ValidationException("vendorId is required for vendor payable account");
    }

    glBootstrapService.ensureDefaultsForShop(shopId);
    String sid = shopId.trim();
    String vid = vendorId.trim();
    String code = vendorPayableCode(vid);

    return glAccountRepository
        .findFirstByShopIdAndCodeOrderByIdAsc(sid, code)
        .map(a -> maybeRefreshLabel(a, vendorDisplayName))
        .orElseGet(() -> insertNew(sid, vid, code, vendorDisplayName));
  }

  /** Public for tests and optional API — matches {@link #resolveOrCreateVendorPayable} rules. */
  public static String vendorPayableCode(String vendorId) {
    if (!StringUtils.hasText(vendorId)) {
      return VENDOR_PAYABLE_CODE_PREFIX + "UNKNOWN";
    }
    String sanitized =
        vendorId.trim().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
    if (!StringUtils.hasText(sanitized)) {
      sanitized = "UNKNOWN";
    }
    return VENDOR_PAYABLE_CODE_PREFIX + sanitized;
  }

  private GlAccount maybeRefreshLabel(GlAccount account, String vendorDisplayName) {
    if (!StringUtils.hasText(vendorDisplayName)) {
      return account;
    }
    String next = truncate(labelFor(vendorDisplayName), 200);
    if (next.equals(account.getName())) {
      return account;
    }
    account.setName(next);
    return glAccountRepository.save(account);
  }

  private GlAccount insertNew(String shopId, String vendorId, String code, String vendorDisplayName) {
    GlAccount row = new GlAccount();
    row.setShopId(shopId);
    row.setCode(code);
    row.setName(truncate(labelFor(vendorDisplayName), 200));
    row.setAccountType(AccountType.LIABILITY);
    row.setSystemAccount(false);
    row.setActive(true);
    row.setCreatedAt(Instant.now());
    try {
      return glAccountRepository.save(row);
    } catch (DuplicateKeyException e) {
      return glAccountRepository
          .findFirstByShopIdAndCodeOrderByIdAsc(shopId, code)
          .map(a -> maybeRefreshLabel(a, vendorDisplayName))
          .orElseThrow(
              () ->
                  new IllegalStateException(
                      "Vendor payable row missing after duplicate insert: " + vendorId));
    }
  }

  private static String labelFor(String vendorDisplayName) {
    if (StringUtils.hasText(vendorDisplayName)) {
      return "Payable · " + vendorDisplayName.trim().replace('\n', ' ');
    }
    return "Vendor payable";
  }

  private static String truncate(String s, int max) {
    if (s == null || s.length() <= max) {
      return s;
    }
    return s.substring(0, max);
  }
}
