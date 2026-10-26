package com.inventory.hr.service;

import com.inventory.common.constants.ErrorCode;
import com.inventory.common.exception.AuthenticationException;
import com.inventory.common.exception.BaseException;
import com.inventory.hr.domain.model.enums.HrPermission;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Action-level HR check, called first in every HR service method (R1.7); the UI hiding a button is
 * never the only check. Failures are 403 {@code ACCESS_DENIED}, never 401 (401 logs the user out).
 */
@Service
public class HrPermissionGuard {

  static final String DENIED = "You do not have permission to do this in HR";

  @Autowired private HrAccessService hrAccessService;

  /**
   * Requires every given permission.
   *
   * @return the caller's effective permissions, so the caller can decide masking without a
   *     second read
   */
  public Set<HrPermission> require(String userId, String shopId, HrPermission... required) {
    Set<HrPermission> effective = effective(userId, shopId);
    for (HrPermission p : required) {
      if (!effective.contains(p)) {
        throw new BaseException(ErrorCode.ACCESS_DENIED, DENIED);
      }
    }
    return effective;
  }

  /** Requires at least one HR permission (read-only HR screens such as settings and holidays). */
  public Set<HrPermission> requireAny(String userId, String shopId) {
    Set<HrPermission> effective = effective(userId, shopId);
    if (effective.isEmpty()) {
      throw new BaseException(ErrorCode.ACCESS_DENIED, DENIED);
    }
    return effective;
  }

  /** Requires at least one of the given permissions (e.g. cancel leave: approve OR mark). */
  public Set<HrPermission> requireOneOf(String userId, String shopId, HrPermission... options) {
    Set<HrPermission> effective = effective(userId, shopId);
    for (HrPermission p : options) {
      if (effective.contains(p)) {
        return effective;
      }
    }
    throw new BaseException(ErrorCode.ACCESS_DENIED, DENIED);
  }

  /** Owner-only HR actions (the role grid). */
  public void requireOwner(String userId, String shopId) {
    requireContext(userId, shopId);
    if (!hrAccessService.isOwner(userId, shopId)) {
      throw new BaseException(ErrorCode.ACCESS_DENIED, "Only the shop owner can change this");
    }
  }

  private Set<HrPermission> effective(String userId, String shopId) {
    requireContext(userId, shopId);
    return hrAccessService.permissions(userId, shopId);
  }

  /** E7: a service never runs without the caller and the tenant shop. */
  static void requireContext(String userId, String shopId) {
    if (!StringUtils.hasText(userId)) {
      throw new AuthenticationException(ErrorCode.UNAUTHORIZED, "User not authenticated");
    }
    if (!StringUtils.hasText(shopId)) {
      throw new AuthenticationException(ErrorCode.UNAUTHORIZED, "Shop context is required");
    }
  }
}
