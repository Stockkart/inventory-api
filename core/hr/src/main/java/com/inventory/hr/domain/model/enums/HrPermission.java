package com.inventory.hr.domain.model.enums;

import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * One thing a role may do in HR (R1.1). The Owner always has all of them; Admin, Manager and
 * Cashier get a row in the shop's role grid ({@code hr_role_permissions}).
 */
public enum HrPermission {
  VIEW_ATTENDANCE,
  MARK_ATTENDANCE,
  APPROVE_LEAVE,
  VIEW_EMPLOYEES,
  ADD_EDIT_EMPLOYEES,
  VIEW_SALARIES,
  RUN_PAYROLL,
  RECORD_PAYMENTS,
  MANAGE_ADVANCES,
  MANAGE_SETTINGS,
  /**
   * Use "Check in" / "Check out" on My HR (self check-in, R2.2). A self-service permission: it
   * opens no HR staff screen (see {@code HrPermissionGuard#requireAny}).
   */
  SELF_CHECK_IN;

  /**
   * Permissions this one cannot work without. Marking attendance or approving leave needs the
   * register; editing employees needs the list; payroll, payments and advances show amounts, so
   * they need VIEW_SALARIES too — otherwise a role could run payroll and see pay without "view
   * salaries", which R4.13 forbids.
   */
  public List<HrPermission> requires() {
    return switch (this) {
      case MARK_ATTENDANCE, APPROVE_LEAVE -> List.of(VIEW_ATTENDANCE);
      case ADD_EDIT_EMPLOYEES -> List.of(VIEW_EMPLOYEES);
      case RUN_PAYROLL, RECORD_PAYMENTS, MANAGE_ADVANCES -> List.of(VIEW_SALARIES, VIEW_EMPLOYEES);
      default -> List.of();
    };
  }

  /**
   * The given set plus everything it requires. One pass is enough because no required permission
   * requires anything itself; the result is idempotent ({@code closure(closure(x)) == closure(x)}).
   */
  public static Set<HrPermission> closure(Collection<HrPermission> permissions) {
    EnumSet<HrPermission> closed = EnumSet.noneOf(HrPermission.class);
    if (permissions == null) {
      return closed;
    }
    for (HrPermission p : permissions) {
      if (p != null) {
        closed.add(p);
        closed.addAll(p.requires());
      }
    }
    return closed;
  }
}
