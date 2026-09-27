package com.inventory.user.migration;

import com.inventory.common.audit.AuditEntry;
import com.inventory.common.audit.AuditService;
import com.inventory.common.audit.AuditSource;
import com.inventory.user.domain.model.PlatformRole;
import com.inventory.user.domain.model.UserAccount;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

/**
 * Grants {@link PlatformRole#PLATFORM_ADMIN} to the accounts listed in {@code platform.admin-emails}
 * on startup. Grant-only: removing an email from the list does not revoke the role.
 */
@Component
@Slf4j
public class PlatformAdminBootstrap {

  static final String ACTION_ROLE_GRANTED = "PLATFORM_ROLE_GRANTED";

  @Autowired
  private MongoTemplate mongoTemplate;

  @Autowired
  private AuditService auditService;

  @Value("${platform.admin-emails:}")
  private String adminEmails;

  @EventListener(ApplicationReadyEvent.class)
  @Order(40)
  public void onStartup() {
    Set<String> emails = parse(adminEmails);
    if (emails.isEmpty()) {
      return;
    }
    for (String email : emails) {
      try {
        grant(email);
      } catch (RuntimeException e) {
        log.error("Platform admin grant failed for {}: {}", email, e.getMessage(), e);
      }
    }
  }

  void grant(String email) {
    // Stored emails are not consistently lower-cased, so match case-insensitively.
    Criteria emailMatches = Criteria.where("email").regex("^" + Pattern.quote(email) + "$", "i");
    Query ungranted = new Query(new Criteria().andOperator(emailMatches,
        Criteria.where("platformRoles").ne(PlatformRole.PLATFORM_ADMIN.name())));
    Update update = new Update().addToSet("platformRoles", PlatformRole.PLATFORM_ADMIN.name());

    UserAccount granted;
    while ((granted = mongoTemplate.findAndModify(ungranted, update, UserAccount.class)) != null) {
      auditService.record(AuditEntry.builder()
          .action(ACTION_ROLE_GRANTED)
          .targetType("USER")
          .targetId(granted.getUserId())
          .after(Map.of("platformRole", PlatformRole.PLATFORM_ADMIN.name()))
          .reason("platform.admin-emails")
          .source(AuditSource.SYSTEM)
          .build());
      log.info("Granted PLATFORM_ADMIN to user {}", granted.getUserId());
    }

    if (!mongoTemplate.exists(new Query(emailMatches), UserAccount.class)) {
      log.warn("Platform admin email {} has no account yet; sign up first and restart", email);
    }
  }

  static Set<String> parse(String raw) {
    if (raw == null || raw.isBlank()) {
      return Set.of();
    }
    return Arrays.stream(raw.split(","))
        .map(String::trim)
        .filter(s -> !s.isEmpty())
        .map(String::toLowerCase)
        .collect(Collectors.toCollection(LinkedHashSet::new));
  }
}
