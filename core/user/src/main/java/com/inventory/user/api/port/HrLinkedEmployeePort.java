package com.inventory.user.api.port;

/**
 * What the user module needs from HR for the Users page's "Workers" group (worker-app R2.5): the
 * code of the employee a login is linked to. HR owns employees and sits above user, so HR
 * implements this port in its {@code service/api/} package (task 4.3); without an implementation
 * the user module shows no code.
 */
public interface HrLinkedEmployeePort {

  /**
   * The code (for example {@code EMP-014}) of the shop's employee whose {@code userId} is this
   * login, or null when the login is linked to no employee of the shop.
   */
  String linkedEmployeeCode(String shopId, String userId);
}
