package com.inventory.hr.service;

import static com.inventory.hr.HrTestFixtures.SHOP;
import static com.inventory.hr.HrTestFixtures.USER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.inventory.common.exception.ResourceNotFoundException;
import com.inventory.hr.HrTestFixtures;
import com.inventory.hr.domain.model.HrNotification;
import com.inventory.hr.domain.model.enums.HrNotificationType;
import com.inventory.hr.domain.model.enums.HrPermission;
import com.inventory.hr.domain.model.enums.NotificationAudience;
import com.inventory.hr.rest.dto.response.HrNotificationsResponse;
import com.mongodb.client.result.UpdateResult;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

/** Inbox audience rules (R6.9). */
@ExtendWith(MockitoExtension.class)
class HrNotificationServiceTest {

  @Mock private MongoTemplate mongoTemplate;
  @Mock private HrAccessService hrAccessService;
  @Spy private HrQueryRunner queryRunner = new HrQueryRunner();
  @InjectMocks private HrNotificationService service;

  private static HrNotification row(String id, NotificationAudience audience, long at) {
    HrNotification n = new HrNotification();
    n.setId(id);
    n.setShopId(SHOP);
    n.setAudience(audience);
    n.setRecipientUserId(audience == NotificationAudience.USER ? USER : null);
    n.setType(HrNotificationType.LEAVE_REQUESTED);
    n.setText("Ravi asked for leave: 2025-04-08");
    n.setCreatedAt(Instant.ofEpochSecond(at));
    return n;
  }

  @Test
  void aNonApproverReadsOnlyTheirOwnRowsInOneQuery() {
    when(mongoTemplate.find(any(Query.class), eq(HrNotification.class)))
        .thenReturn(List.of(row("u", NotificationAudience.USER, 10)));
    when(hrAccessService.permissions(USER, SHOP))
        .thenReturn(EnumSet.of(HrPermission.VIEW_ATTENDANCE));

    HrNotificationsResponse r = service.listForCaller(USER, SHOP, Instant.ofEpochSecond(5));

    assertEquals(List.of("u"), r.getItems().stream().map(i -> i.getId()).toList());
    assertEquals(Instant.ofEpochSecond(10), r.getCursor());
    ArgumentCaptor<Query> q = ArgumentCaptor.forClass(Query.class);
    verify(mongoTemplate).find(q.capture(), eq(HrNotification.class));
    // approver rows are not even asked for, so they can never take the caller's places
    assertFalse(q.getValue().getQueryObject().toString().contains("audience"));
    assertTrue(q.getValue().getQueryObject().toString().contains("recipientUserId"));
  }

  @Test
  void approversSeeTheirRows() {
    when(mongoTemplate.find(any(Query.class), eq(HrNotification.class)))
        .thenReturn(List.of(row("a", NotificationAudience.APPROVERS, 20)));
    when(hrAccessService.permissions(USER, SHOP)).thenReturn(HrTestFixtures.all());

    assertEquals(1, service.listForCaller(USER, SHOP, null).getItems().size());
    ArgumentCaptor<Query> q = ArgumentCaptor.forClass(Query.class);
    verify(mongoTemplate).find(q.capture(), eq(HrNotification.class));
    assertTrue(q.getValue().getQueryObject().toString().contains("APPROVERS"));
  }

  @Test
  void aQuietPollIsOnePermissionResolveAndOneQueryAndKeepsTheCursor() {
    when(mongoTemplate.find(any(Query.class), eq(HrNotification.class))).thenReturn(List.of());
    when(hrAccessService.permissions(USER, SHOP))
        .thenReturn(EnumSet.of(HrPermission.VIEW_ATTENDANCE));
    Instant since = Instant.ofEpochSecond(99);

    HrNotificationsResponse r = service.listForCaller(USER, SHOP, since);

    assertTrue(r.getItems().isEmpty());
    assertEquals(since, r.getCursor());
    verify(hrAccessService).permissions(USER, SHOP);
    ArgumentCaptor<Query> q = ArgumentCaptor.forClass(Query.class);
    verify(mongoTemplate).find(q.capture(), eq(HrNotification.class));
    assertEquals(HrNotificationService.PAGE_SIZE, q.getValue().getLimit());
  }

  @Test
  void markingSomebodyElsesRowIs404() {
    when(mongoTemplate.updateFirst(any(Query.class), any(Update.class),
        eq(HrNotification.class))).thenReturn(UpdateResult.acknowledged(0, 0L, null));

    assertThrows(ResourceNotFoundException.class, () -> service.markRead(USER, SHOP, "n1"));
  }

  @Test
  void writesCarryTheAudience() {
    service.notifyApprovers(SHOP, HrNotificationType.LEAVE_REQUESTED, "l1", "text");
    ArgumentCaptor<HrNotification> n = ArgumentCaptor.forClass(HrNotification.class);
    verify(mongoTemplate).insert(n.capture());
    assertEquals(NotificationAudience.APPROVERS, n.getValue().getAudience());
    assertFalse(n.getValue().getText().isEmpty());
  }
}
