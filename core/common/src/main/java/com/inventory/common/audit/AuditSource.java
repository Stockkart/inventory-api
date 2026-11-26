package com.inventory.common.audit;

/** Where a privileged change came from. */
public enum AuditSource {
  ADMIN_UI,
  SYSTEM,
  MIGRATION
}
