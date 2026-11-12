package com.inventory.app.interceptor;

import com.inventory.common.constants.ErrorCode;
import com.inventory.common.exception.BaseException;
import com.inventory.user.rest.dto.response.ShopAccessResponse;
import com.inventory.user.service.RbacService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Enforces shop RBAC module and team permissions on protected API paths. Runs after authentication
 * so {@code userId} and {@code shopId} request attributes are available.
 */
@Component
@Slf4j
public class RbacModuleInterceptor implements HandlerInterceptor {

  private static final List<Pattern> SKIP_PATHS =
      List.of(
          Pattern.compile("/api/v1/shops/me/access"),
          Pattern.compile("/api/v1/shops/[a-fA-F0-9]{24}/rbac(/.*)?"),
          Pattern.compile("/api/v1/plans$"),
          Pattern.compile("/api/v1/plans/[a-fA-F0-9]{24}$"),
          Pattern.compile("/api/v1/plans/shop/status"),
          Pattern.compile("/api/v1/plans/payment/config"),
          Pattern.compile("/api/v1/plans/payment/webhook/.*"),
          // HR self-service: every linked employee may see their own HR data whatever their role
          // or HR switch (R1.5). It still needs a bearer token and a shop; the handlers resolve
          // the employee from (shopId, caller userId) only, never from the path or body.
          Pattern.compile("/api/v1/hr/me(/.*)?"));

  private static final List<ModuleRule> MODULE_RULES =
      List.of(
          new ModuleRule("/api/v1/accounting", RbacService.MODULE_ACCOUNTING),
          new ModuleRule("/api/v1/analytics", RbacService.MODULE_ANALYTICS),
          new ModuleRule("/api/v1/mis", RbacService.MODULE_MIS),
          new ModuleRule("/api/v1/taxation", RbacService.MODULE_TAXES),
          new ModuleRule("/api/v1/hr", RbacService.MODULE_HR),
          new ModuleRule("/api/v1/plans/shop/transactions", RbacService.MODULE_PAYMENT_PLAN),
          new ModuleRule("/api/v1/plans/shop/usage", RbacService.MODULE_PAYMENT_PLAN),
          new ModuleRule("/api/v1/plans/payment/checkout", RbacService.MODULE_PAYMENT_PLAN),
          new ModuleRule("/api/v1/plans/payment/verify", RbacService.MODULE_PAYMENT_PLAN));

  private static final List<PatternModuleRule> PATTERN_MODULE_RULES =
      List.of(
          new PatternModuleRule(
              Pattern.compile("/api/v1/plans/shop/[a-fA-F0-9]{24}/(assign|suggested)"),
              RbacService.MODULE_PAYMENT_PLAN));

  private static final List<Pattern> STOCK_CORRECTION_APPROVAL_PATHS =
      List.of(
          Pattern.compile("/api/v1/inventory-corrections/[^/]+/lines/[^/]+/approve"),
          Pattern.compile("/api/v1/inventory-corrections/[^/]+/lines/[^/]+/reject"));

  /**
   * Pricing writes change a lot's rates and default rate, so they need the same product-search
   * grant as sending those fields to {@code PUT /api/v1/inventory/{id}}. The body is not read
   * here: both endpoints only set rates (including resets) and the default rate.
   */
  private static final Pattern PRICING_PATCH_PATH = Pattern.compile("/api/v1/pricing/[^/]+");

  private static final String PRICING_BULK_UPDATE_PATH = "/api/v1/pricing/bulk-update";

  static final Set<String> PRICING_WRITE_FIELDS = Set.of("rates", "defaultRate");

  private static final List<TeamRule> TEAM_RULES =
      List.of(
          new TeamRule(
              Pattern.compile("/api/v1/shops/[a-fA-F0-9]{24}/invitations"),
              RbacService.TEAM_MANAGE_INVITATIONS),
          new TeamRule(
              Pattern.compile("/api/v1/shops/join-requests(/.*)?"),
              RbacService.TEAM_MANAGE_JOIN_REQUESTS),
          new TeamRule(
              Pattern.compile("/api/v1/shops/[a-fA-F0-9]{24}/users(/.*)?"),
              RbacService.TEAM_MANAGE_SHOP_USERS),
          new TeamRule(
              Pattern.compile("/api/v1/users/invitations"),
              RbacService.TEAM_VIEW_MY_INVITATIONS));

  @Autowired private RbacService rbacService;

  @Override
  public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
    if ("OPTIONS".equals(request.getMethod())) {
      return true;
    }

    // Rules match the decoded path Spring routes on, not the raw URI: a raw
    // "/api/v1/accounting;jsessionid=1/x" reaches the accounting controller but does not start
    // with "/api/v1/accounting/". Dot segments and encoded separators are refused (403) because
    // the container and Spring may disagree about where such a path leads.
    String path = RequestPaths.lookupPath(request);
    if (RequestPaths.isSuspicious(request, path)) {
      log.warn("RBAC refused a request path with dot segments or encoded separators");
      throw new BaseException(ErrorCode.ACCESS_DENIED, "Access denied");
    }
    if (shouldSkip(path)) {
      return true;
    }

    String userId = (String) request.getAttribute("userId");
    String shopId = (String) request.getAttribute("shopId");
    if (!StringUtils.hasText(userId) || !StringUtils.hasText(shopId)) {
      return true;
    }

    String moduleKey = resolveModuleKey(path);
    if (moduleKey != null) {
      log.debug("RBAC module check: user={} shop={} module={} path={}", userId, shopId, moduleKey, path);
      ShopAccessResponse access = rbacService.requireModule(userId, shopId, moduleKey);
      // Kept for the rest of the request so module services (HR permission checks) do not
      // resolve the same access again. Request-scoped only; nothing is cached across requests.
      request.setAttribute(RbacService.ACCESS_ATTRIBUTE, access);
      return true;
    }

    String teamKey = resolveTeamKey(path);
    if (teamKey != null) {
      log.debug("RBAC team check: user={} shop={} team={} path={}", userId, shopId, teamKey, path);
      rbacService.requireTeamAccess(userId, shopId, teamKey);
      return true;
    }

    if (requiresStockCorrectionApproval(path)) {
      log.debug("RBAC stock correction approval: user={} shop={} path={}", userId, shopId, path);
      rbacService.requireStockCorrectionApproval(userId, shopId);
    }

    if (isPricingWrite(request.getMethod(), path)) {
      log.debug("RBAC pricing write: user={} shop={} path={}", userId, shopId, path);
      rbacService.validateProductSearchFieldUpdates(userId, shopId, PRICING_WRITE_FIELDS);
    }

    return true;
  }

  private static boolean isPricingWrite(String method, String path) {
    if ("POST".equals(method)) {
      return PRICING_BULK_UPDATE_PATH.equals(path);
    }
    return "PATCH".equals(method) && PRICING_PATCH_PATH.matcher(path).matches();
  }

  private static boolean requiresStockCorrectionApproval(String path) {
    return STOCK_CORRECTION_APPROVAL_PATHS.stream().anyMatch(p -> p.matcher(path).matches());
  }

  private static boolean shouldSkip(String path) {
    // getRequestURI() is not normalised: a "/hr/me/../employees" or ";param" URI would match a
    // skip pattern yet be dispatched elsewhere, so such URIs never skip the checks.
    // preHandle now passes the decoded, semicolon-free path and refuses dot segments before this
    // runs; the check stays as a second line of defence.
    if (path.contains("..") || path.contains(";")) {
      return false;
    }
    return SKIP_PATHS.stream().anyMatch(pattern -> pattern.matcher(path).matches());
  }

  private static String resolveModuleKey(String path) {
    for (PatternModuleRule rule : PATTERN_MODULE_RULES) {
      if (rule.pattern().matcher(path).matches()) {
        return rule.moduleKey();
      }
    }
    for (ModuleRule rule : MODULE_RULES) {
      if (path.equals(rule.prefix()) || path.startsWith(rule.prefix() + "/")) {
        return rule.moduleKey();
      }
    }
    return null;
  }

  private static String resolveTeamKey(String path) {
    for (TeamRule rule : TEAM_RULES) {
      if (rule.pattern.matcher(path).matches()) {
        return rule.teamKey;
      }
    }
    return null;
  }

  private static final class ModuleRule {
    private final String prefix;
    private final String moduleKey;

    private ModuleRule(String prefix, String moduleKey) {
      this.prefix = prefix;
      this.moduleKey = moduleKey;
    }

    private String prefix() {
      return prefix;
    }

    private String moduleKey() {
      return moduleKey;
    }
  }

  private static final class PatternModuleRule {
    private final Pattern pattern;
    private final String moduleKey;

    private PatternModuleRule(Pattern pattern, String moduleKey) {
      this.pattern = pattern;
      this.moduleKey = moduleKey;
    }

    private Pattern pattern() {
      return pattern;
    }

    private String moduleKey() {
      return moduleKey;
    }
  }

  private static final class TeamRule {
    private final Pattern pattern;
    private final String teamKey;

    private TeamRule(Pattern pattern, String teamKey) {
      this.pattern = pattern;
      this.teamKey = teamKey;
    }
  }
}
