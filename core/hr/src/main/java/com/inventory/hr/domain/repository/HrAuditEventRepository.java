package com.inventory.hr.domain.repository;

import com.inventory.hr.domain.model.HrAuditEvent;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

/** Append-only: {@code HrAuditService} only inserts; nothing updates or deletes audit rows. */
@Repository
public interface HrAuditEventRepository extends MongoRepository<HrAuditEvent, String> {}
