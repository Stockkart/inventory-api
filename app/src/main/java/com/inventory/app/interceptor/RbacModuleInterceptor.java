package com.inventory.app.interceptor;

import com.inventory.user.service.RbacService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
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
          Pattern.compile("/api/v1/plans/payment/webhook/.*"));

  private static final List<ModuleRule> MODULE_RULES =
      List.of(
          new ModuleRule("/api/v1/accounting", RbacService.MODULE_ACCOUNTING),
          new ModuleRule("/api/v1/analytics", RbacService.MODULE_ANALYTICS),
          new ModuleRule("/api/v1/taxation", RbacService.MODULE_TAXES),
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

    String path = request.getRequestURI();
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
      rbacService.requireModule(userId, shopId, moduleKey);
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

    return true;
  }

  private static boolean requiresStockCorrectionApproval(String path) {
    return STOCK_CORRECTION_APPROVAL_PATHS.stream().anyMatch(p -> p.matcher(path).matches());
  }

  private static boolean shouldSkip(String path) {
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
