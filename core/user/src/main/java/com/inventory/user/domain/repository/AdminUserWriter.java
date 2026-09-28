package com.inventory.user.domain.repository;

import com.inventory.user.domain.model.AdminUser;
import java.time.Duration;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

/** Field-level writes to {@code admin_users}, so concurrent sign-ins never overwrite each other. */
@Component
public class AdminUserWriter {

  @Autowired
  private MongoTemplate mongoTemplate;

  /**
   * Counts a failed sign-in. The attempt that reaches {@code maxAttempts} locks the account until
   * {@code now + lockFor} and starts a fresh count.
   *
   * @return true when this attempt locked the account
   */
  public boolean recordFailedLogin(String adminId, Instant now, int maxAttempts, Duration lockFor) {
    AdminUser after = mongoTemplate.findAndModify(
        byId(adminId),
        new Update().inc("failedLoginCount", 1).set("updatedAt", now),
        FindAndModifyOptions.options().returnNew(true),
        AdminUser.class);
    if (after == null || after.getFailedLoginCount() < maxAttempts) {
      return false;
    }
    mongoTemplate.updateFirst(
        new Query(Criteria.where("_id").is(adminId).and("failedLoginCount").gte(maxAttempts)),
        new Update().set("failedLoginCount", 0).set("lockedUntil", now.plus(lockFor)),
        AdminUser.class);
    return true;
  }

  public void recordSuccessfulLogin(String adminId, Instant now) {
    mongoTemplate.updateFirst(byId(adminId), new Update()
        .set("failedLoginCount", 0)
        .unset("lockedUntil")
        .set("lastLoginAt", now)
        .set("updatedAt", now), AdminUser.class);
  }

  /** Replaces the password and clears any lockout. */
  public void setPassword(String adminId, String passwordHash, boolean mustChange, Instant now) {
    mongoTemplate.updateFirst(byId(adminId), new Update()
        .set("passwordHash", passwordHash)
        .set("mustChangePassword", mustChange)
        .set("failedLoginCount", 0)
        .unset("lockedUntil")
        .set("updatedAt", now), AdminUser.class);
  }

  public void setActive(String adminId, boolean active, Instant now) {
    mongoTemplate.updateFirst(byId(adminId), new Update()
        .set("active", active)
        .set("updatedAt", now), AdminUser.class);
  }

  private static Query byId(String adminId) {
    return new Query(Criteria.where("_id").is(adminId));
  }
}
