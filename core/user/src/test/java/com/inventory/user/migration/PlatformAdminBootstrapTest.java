package com.inventory.user.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.inventory.common.audit.AuditEntry;
import com.inventory.common.audit.AuditService;
import com.inventory.common.audit.AuditSource;
import com.inventory.user.domain.model.UserAccount;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class PlatformAdminBootstrapTest {

  @Mock
  private MongoTemplate mongoTemplate;

  @Mock
  private AuditService auditService;

  @InjectMocks
  private PlatformAdminBootstrap bootstrap;

  @Test
  void parsesTrimsLowercasesAndDedupes() {
    assertThat(PlatformAdminBootstrap.parse(" Ops@StockKart.in, ,ops@stockkart.in,dev@x.io "))
        .containsExactly("ops@stockkart.in", "dev@x.io");
    assertThat(PlatformAdminBootstrap.parse("")).isEmpty();
    assertThat(PlatformAdminBootstrap.parse(null)).isEmpty();
  }

  @Test
  void doesNothingWhenNoEmailsConfigured() {
    ReflectionTestUtils.setField(bootstrap, "adminEmails", "");

    bootstrap.onStartup();

    verifyNoInteractions(mongoTemplate, auditService);
  }

  @Test
  void grantsAndAuditsEachMatchingAccountOnce() {
    UserAccount first = account("u1");
    UserAccount second = account("u2");
    when(mongoTemplate.findAndModify(any(Query.class), any(Update.class), eq(UserAccount.class)))
        .thenReturn(first, second, null);
    when(mongoTemplate.exists(any(Query.class), eq(UserAccount.class))).thenReturn(true);

    bootstrap.grant("ops@stockkart.in");

    ArgumentCaptor<AuditEntry> audit = ArgumentCaptor.forClass(AuditEntry.class);
    verify(auditService, times(2)).record(audit.capture());
    assertThat(audit.getAllValues()).extracting(AuditEntry::getTargetId).containsExactly("u1", "u2");
    assertThat(audit.getValue().getSource()).isEqualTo(AuditSource.SYSTEM);
    assertThat(audit.getValue().getAction()).isEqualTo(PlatformAdminBootstrap.ACTION_ROLE_GRANTED);
  }

  @Test
  void alreadyGrantedAccountIsNotAuditedAgain() {
    when(mongoTemplate.findAndModify(any(Query.class), any(Update.class), eq(UserAccount.class)))
        .thenReturn(null);
    when(mongoTemplate.exists(any(Query.class), eq(UserAccount.class))).thenReturn(true);

    bootstrap.grant("ops@stockkart.in");

    verify(auditService, never()).record(any());
  }

  @Test
  void grantQueryIsCaseInsensitiveAndSkipsExistingAdmins() {
    when(mongoTemplate.findAndModify(any(Query.class), any(Update.class), eq(UserAccount.class)))
        .thenReturn(null);

    bootstrap.grant("ops@stockkart.in");

    ArgumentCaptor<Query> query = ArgumentCaptor.forClass(Query.class);
    verify(mongoTemplate).findAndModify(query.capture(), any(Update.class), eq(UserAccount.class));
    String json = query.getValue().getQueryObject().toJson();
    assertThat(json).contains("\"options\": \"i\"").contains("\"$ne\": \"PLATFORM_ADMIN\"");
  }

  @Test
  void oneFailingEmailDoesNotStopTheRest() {
    ReflectionTestUtils.setField(bootstrap, "adminEmails", "bad@x.io,good@x.io");
    when(mongoTemplate.findAndModify(any(Query.class), any(Update.class), eq(UserAccount.class)))
        .thenThrow(new RuntimeException("boom"))
        .thenReturn(account("u9"), (UserAccount) null);
    when(mongoTemplate.exists(any(Query.class), eq(UserAccount.class))).thenReturn(true);

    bootstrap.onStartup();

    verify(auditService, times(1)).record(any());
  }

  private static UserAccount account(String id) {
    UserAccount account = new UserAccount();
    account.setUserId(id);
    return account;
  }
}
