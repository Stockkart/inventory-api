package com.inventory.common.audit;

import java.time.Instant;
import java.util.Objects;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.stereotype.Service;

/**
 * Appends audit entries. Entries are never updated or deleted.
 *
 * <p>Writes happen after the audited change and a failed write propagates, so the caller sees the
 * failure instead of losing the record silently. There are no transactions, so audited operations
 * must be safe to retry.
 */
@Service
@Slf4j
public class AuditService {

  @Autowired
  private MongoTemplate mongoTemplate;

  @EventListener(ApplicationReadyEvent.class)
  public void ensureIndexes() {
    var ops = mongoTemplate.indexOps(AuditEntry.class);
    ops.ensureIndex(new Index()
        .on("targetType", Sort.Direction.ASC)
        .on("targetId", Sort.Direction.ASC)
        .on("createdAt", Sort.Direction.DESC)
        .named("target_createdAt"));
    ops.ensureIndex(new Index()
        .on("actorUserId", Sort.Direction.ASC)
        .on("createdAt", Sort.Direction.DESC)
        .named("actor_createdAt"));
  }

  public AuditEntry record(AuditEntry entry) {
    Objects.requireNonNull(entry.getAction(), "audit action");
    Objects.requireNonNull(entry.getSource(), "audit source");
    if (entry.getSource() == AuditSource.ADMIN_UI && entry.getActorUserId() == null) {
      throw new IllegalArgumentException("ADMIN_UI audit entries need an actor");
    }
    entry.setId(null);
    entry.setCreatedAt(Instant.now());
    AuditEntry saved = mongoTemplate.insert(entry);
    log.info("Audit {} {} {}/{} by {}", saved.getSource(), saved.getAction(),
        saved.getTargetType(), saved.getTargetId(), saved.getActorUserId());
    return saved;
  }
}
