package com.inventory.common.audit;

import java.time.Instant;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * Who changed money or entitlements, what they changed, and why. Written for every mutation behind
 * the platform-admin gate and for system grants of platform roles.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = AuditEntry.COLLECTION)
public class AuditEntry {

  public static final String COLLECTION = "audit_entries";

  @Id
  private String id;
  /** Null for SYSTEM and MIGRATION entries. */
  private String actorUserId;
  private String action;
  private String targetType;
  private String targetId;
  private Map<String, Object> before;
  private Map<String, Object> after;
  /** Required for manual wallet and reward adjustments. */
  private String reason;
  private AuditSource source;
  private Instant createdAt;
}
