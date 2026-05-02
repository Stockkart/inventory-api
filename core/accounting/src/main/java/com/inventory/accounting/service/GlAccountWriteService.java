package com.inventory.accounting.service;

import com.inventory.accounting.domain.model.DefaultAccountCodes;
import com.inventory.accounting.domain.model.GlAccount;
import com.inventory.accounting.domain.repository.GlAccountRepository;
import com.inventory.accounting.rest.dto.request.CreateGlAccountRequest;
import com.inventory.common.exception.ValidationException;
import java.time.Instant;
import java.util.Locale;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class GlAccountWriteService {

  private static final Pattern CODE_PATTERN = Pattern.compile("^[A-Z0-9][A-Z0-9._-]{1,63}$");


  private final GlAccountRepository glAccountRepository;
  private final GlBootstrapService glBootstrapService;

  @Transactional
  public GlAccount createManualAccount(String shopId, CreateGlAccountRequest request) {
    if (!StringUtils.hasText(shopId)) {
      throw new ValidationException("shopId is required");
    }
    glBootstrapService.ensureDefaultsForShop(shopId);

    String code = normalizeCode(request.getCode());
    validateNewCode(code);
    if (!StringUtils.hasText(request.getName()) || request.getName().isBlank()) {
      throw new ValidationException("Display name is required");
    }
    if (request.getAccountType() == null) {
      throw new ValidationException("Account type is required");
    }

    String name = request.getName().trim();
    if (name.length() > 200) {
      throw new ValidationException("Display name must be at most 200 characters");
    }

    if (glAccountRepository.existsByShopIdAndCode(shopId, code)) {
      throw new ValidationException("An account with this code already exists for this shop");
    }

    boolean active = request.getActive() == null || request.getActive();

    GlAccount row = new GlAccount();
    row.setShopId(shopId.trim());
    row.setCode(code);
    row.setName(name);
    row.setAccountType(request.getAccountType());
    row.setSystemAccount(false);
    row.setActive(active);
    row.setCreatedAt(Instant.now());

    try {
      return glAccountRepository.save(row);
    } catch (DuplicateKeyException e) {
      throw new ValidationException("An account with this code already exists for this shop");
    }
  }

  static String normalizeCode(String raw) {
    if (!StringUtils.hasText(raw)) {
      return "";
    }
    return raw.trim().toUpperCase(Locale.ROOT);
  }

  private static void validateNewCode(String code) {
    if (!StringUtils.hasText(code)) {
      throw new ValidationException("Account code is required");
    }
    if (DefaultAccountCodes.seededCodesUppercase().contains(code)) {
      throw new ValidationException(
          "That code is reserved for a built-in account. Choose another (for example BANK-HDFC or EXP-MARKETING).");
    }
    if (code.startsWith(VendorPayableNominalService.VENDOR_PAYABLE_CODE_PREFIX)) {
      throw new ValidationException(
          "Codes starting with "
              + VendorPayableNominalService.VENDOR_PAYABLE_CODE_PREFIX
              + " are reserved for per-vendor payable accounts (created automatically on purchase).");
    }
    if (!CODE_PATTERN.matcher(code).matches()) {
      throw new ValidationException(
          "Account code must be 2–64 characters: letters, digits, dot, underscore, or hyphen; must start with a letter or digit.");
    }
  }
}
