package com.inventory.user.migration;

import com.inventory.user.domain.model.UserAccount;
import com.inventory.user.domain.repository.UserAccountRepository;
import com.inventory.user.service.UserShopMembershipService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Backfills UserShopMembership for legacy users who have shopId on UserAccount but no membership.
 * Runs once on application startup. Backward compatible - safe to run multiple times.
 */
@Component
@Slf4j
public class UserShopMembershipMigrationRunner {

  @Autowired
  private UserAccountRepository userAccountRepository;

  @Autowired
  private UserShopMembershipService membershipService;

  @EventListener(ApplicationReadyEvent.class)
  public void runMigration() {
    try {
      log.info("Running UserShopMembership migration (backfill for legacy users)");
      List<UserAccount> allUsers = userAccountRepository.findAll();
      int migrated = 0;
      for (UserAccount account : allUsers) {
        if (account.getShopId() != null && !account.getShopId().trim().isEmpty()) {
          membershipService.ensureMembershipForLegacyUser(account);
          migrated++;
        }
      }
      if (migrated > 0) {
        log.info("UserShopMembership migration completed: {} users processed", migrated);
      } else {
        log.debug("UserShopMembership migration: no legacy users to migrate");
      }
    } catch (Exception e) {
      log.error("UserShopMembership migration failed: {}", e.getMessage(), e);
      // Don't fail startup - migration is non-critical
    }
  }
}
