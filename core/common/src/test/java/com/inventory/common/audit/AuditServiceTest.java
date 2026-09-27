package com.inventory.common.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoTemplate;

@ExtendWith(MockitoExtension.class)
class AuditServiceTest {

  @Mock
  private MongoTemplate mongoTemplate;

  @InjectMocks
  private AuditService auditService;

  @Test
  void insertsWithServerTimestampAndFreshId() {
    when(mongoTemplate.insert(any(AuditEntry.class))).thenAnswer(inv -> inv.getArgument(0));

    AuditEntry saved = auditService.record(AuditEntry.builder()
        .id("client-supplied")
        .actorUserId("admin-1")
        .action("SHOP_APPROVED")
        .source(AuditSource.ADMIN_UI)
        .build());

    assertThat(saved.getId()).isNull();
    assertThat(saved.getCreatedAt()).isNotNull();
  }

  @Test
  void rejectsAdminEntryWithoutActor() {
    assertThatThrownBy(() -> auditService.record(AuditEntry.builder()
        .action("SHOP_APPROVED")
        .source(AuditSource.ADMIN_UI)
        .build()))
        .isInstanceOf(IllegalArgumentException.class);
    verify(mongoTemplate, never()).insert(any(AuditEntry.class));
  }

  @Test
  void allowsSystemEntryWithoutActor() {
    when(mongoTemplate.insert(any(AuditEntry.class))).thenAnswer(inv -> inv.getArgument(0));

    auditService.record(AuditEntry.builder()
        .action("PLATFORM_ROLE_GRANTED")
        .source(AuditSource.SYSTEM)
        .build());

    verify(mongoTemplate).insert(any(AuditEntry.class));
  }

  @Test
  void rejectsEntryWithoutSource() {
    assertThatThrownBy(() -> auditService.record(AuditEntry.builder().action("X").build()))
        .isInstanceOf(NullPointerException.class);
  }
}
