package com.inventory.hr.service;

import static com.inventory.hr.HrTestFixtures.SHOP;
import static com.inventory.hr.HrTestFixtures.USER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.inventory.common.exception.AuthenticationException;
import com.inventory.common.exception.ResourceNotFoundException;
import com.inventory.hr.HrTestFixtures;
import com.inventory.hr.domain.model.HrNotification;
import com.inventory.hr.domain.model.enums.HrNotificationType;
import com.inventory.hr.domain.model.enums.HrPermission;
import com.inventory.hr.domain.model.enums.NotificationAudience;
import com.inventory.hr.rest.dto.response.HrAccountNotificationsResponse;
import com.inventory.hr.rest.dto.response.HrNotificationsResponse;
import com.mongodb.client.result.UpdateResult;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.bson.Document;
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

/** Inbox audience rules (R6.9; worker-app R4.13, R10.1–R10.3, R10.5). */
@ExtendWith(MockitoExtension.class)
class HrNotificationServiceTest {

  private static final String PHONE = "9876543210";
  private static final String EMAIL = "priya@example.com";

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

  private static HrNotification workerRow(String id, String shopId, long at, boolean read) {
    HrNotification n = new HrNotification();
    n.setId(id);
    n.setShopId(shopId);
    n.setAudience(NotificationAudience.USER);
    n.setRecipientUserId(USER);
    n.setType(HrNotificationType.JOIN_REQUEST_APPROVED);
    n.setText("Sri Ganesh Stores accepted you");
    n.setCreatedAt(Instant.ofEpochSecond(at));
    n.setReadByUserIds(read ? new ArrayList<>(List.of(USER)) : new ArrayList<>());
    return n;
  }

  private static String queryText(Query q) {
    return q.getQueryObject().toString();
  }

  // ---- listForCaller: per-type approver gating ------------------------------------------------

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
    assertFalse(queryText(q.getValue()).contains("audience"));
    assertTrue(queryText(q.getValue()).contains("recipientUserId"));
  }

  @Test
  void approversSeeTheirRows() {
    when(mongoTemplate.find(any(Query.class), eq(HrNotification.class)))
        .thenReturn(List.of(row("a", NotificationAudience.APPROVERS, 20)));
    when(hrAccessService.permissions(USER, SHOP)).thenReturn(HrTestFixtures.all());

    assertEquals(1, service.listForCaller(USER, SHOP, null).getItems().size());
    ArgumentCaptor<Query> q = ArgumentCaptor.forClass(Query.class);
    verify(mongoTemplate).find(q.capture(), eq(HrNotification.class));
    String text = queryText(q.getValue());
    assertTrue(text.contains("APPROVERS"));
    // every permission: both kinds of approver row
    assertTrue(text.contains("LEAVE_REQUESTED"));
    assertTrue(text.contains("JOIN_REQUEST_RECEIVED"));
  }

  @Test
  void aLeaveApproverWithoutAddEditEmployeesDoesNotSeeJoinRequestRows() {
    when(mongoTemplate.find(any(Query.class), eq(HrNotification.class))).thenReturn(List.of());
    when(hrAccessService.permissions(USER, SHOP))
        .thenReturn(EnumSet.of(HrPermission.APPROVE_LEAVE));

    service.listForCaller(USER, SHOP, null);

    ArgumentCaptor<Query> q = ArgumentCaptor.forClass(Query.class);
    verify(mongoTemplate).find(q.capture(), eq(HrNotification.class));
    String text = queryText(q.getValue());
    assertTrue(text.contains("APPROVERS"));
    assertTrue(text.contains("LEAVE_REQUESTED"));
    assertFalse(text.contains("JOIN_REQUEST_RECEIVED"));
  }

  @Test
  void anEmployeeEditorWithoutApproveLeaveSeesJoinRequestRowsOnly() {
    when(mongoTemplate.find(any(Query.class), eq(HrNotification.class))).thenReturn(List.of());
    when(hrAccessService.permissions(USER, SHOP))
        .thenReturn(EnumSet.of(HrPermission.ADD_EDIT_EMPLOYEES));

    service.listForCaller(USER, SHOP, null);

    ArgumentCaptor<Query> q = ArgumentCaptor.forClass(Query.class);
    verify(mongoTemplate).find(q.capture(), eq(HrNotification.class));
    String text = queryText(q.getValue());
    assertTrue(text.contains("APPROVERS"));
    assertTrue(text.contains("JOIN_REQUEST_RECEIVED"));
    assertFalse(text.contains("LEAVE_REQUESTED"));
  }

  @Test
  void approverTypesFollowThePermissions() {
    assertEquals(Set.of(), HrNotificationService.approverTypesFor(
        EnumSet.of(HrPermission.VIEW_EMPLOYEES, HrPermission.SELF_CHECK_IN)));
    assertEquals(HrNotificationService.LEAVE_TYPES,
        HrNotificationService.approverTypesFor(EnumSet.of(HrPermission.APPROVE_LEAVE)));
    assertEquals(HrNotificationService.JOIN_APPROVER_TYPES,
        HrNotificationService.approverTypesFor(EnumSet.of(HrPermission.ADD_EDIT_EMPLOYEES)));
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
  void theShopBellCarriesTheJoinRequestId() {
    HrNotification n = row("a", NotificationAudience.APPROVERS, 20);
    n.setType(HrNotificationType.JOIN_REQUEST_RECEIVED);
    n.setLeaveRequestId(null);
    n.setJoinRequestId("jr-1");
    when(mongoTemplate.find(any(Query.class), eq(HrNotification.class))).thenReturn(List.of(n));
    when(hrAccessService.permissions(USER, SHOP)).thenReturn(HrTestFixtures.all());

    HrNotificationsResponse r = service.listForCaller(USER, SHOP, null);

    assertEquals("jr-1", r.getItems().get(0).getJoinRequestId());
    assertNull(r.getItems().get(0).getLeaveRequestId());
  }

  @Test
  void aLeaveRowInTheBellCarriesItsLeaveRequestAndNoJoinRequestId() {
    HrNotification n = row("l", NotificationAudience.APPROVERS, 20);
    n.setLeaveRequestId("leave-1");
    when(mongoTemplate.find(any(Query.class), eq(HrNotification.class))).thenReturn(List.of(n));
    when(hrAccessService.permissions(USER, SHOP)).thenReturn(HrTestFixtures.all());

    HrNotificationsResponse r = service.listForCaller(USER, SHOP, null);

    assertEquals(HrNotificationType.LEAVE_REQUESTED, r.getItems().get(0).getType());
    assertEquals("leave-1", r.getItems().get(0).getLeaveRequestId());
    assertNull(r.getItems().get(0).getJoinRequestId());
  }

  // ---- markRead ------------------------------------------------------------------------------

  @Test
  void markingSomebodyElsesRowIs404() {
    when(mongoTemplate.updateFirst(any(Query.class), any(Update.class),
        eq(HrNotification.class))).thenReturn(UpdateResult.acknowledged(0, 0L, null));

    assertThrows(ResourceNotFoundException.class, () -> service.markRead(USER, SHOP, "n1"));
  }

  @Test
  void accountMarkReadTouchesOnlyTheCallersOwnUserRows() {
    when(mongoTemplate.updateFirst(any(Query.class), any(Update.class),
        eq(HrNotification.class))).thenReturn(UpdateResult.acknowledged(1, 1L, null));

    service.markRead(USER, "n1");

    ArgumentCaptor<Query> q = ArgumentCaptor.forClass(Query.class);
    verify(mongoTemplate).updateFirst(q.capture(), any(Update.class), eq(HrNotification.class));
    Document filter = q.getValue().getQueryObject();
    assertEquals("n1", filter.get("_id"));
    assertEquals(NotificationAudience.USER, filter.get("audience"));
    assertEquals(USER, filter.get("recipientUserId"));
    // no shop context in the filter: the row is addressed to the account, not a shop
    assertFalse(filter.containsKey("shopId"));
  }

  @Test
  void accountMarkReadOfAnotherRowIs404() {
    when(mongoTemplate.updateFirst(any(Query.class), any(Update.class),
        eq(HrNotification.class))).thenReturn(UpdateResult.acknowledged(0, 0L, null));

    assertThrows(ResourceNotFoundException.class, () -> service.markRead(USER, "approver-row"));
  }

  @Test
  void accountReadsNeedACaller() {
    assertThrows(AuthenticationException.class, () -> service.listForAccount(" ", 3));
    assertThrows(AuthenticationException.class, () -> service.markRead(null, "n1"));
  }

  // ---- listForAccount: across shops, no shop context, unread first then newest ----------------

  @Test
  void listForAccountReadsTheAccountsRowsAcrossShopsWithoutShopContext() {
    when(mongoTemplate.count(any(Query.class), eq(HrNotification.class))).thenReturn(2L);
    when(mongoTemplate.find(any(Query.class), eq(HrNotification.class)))
        .thenReturn(List.of(workerRow("b", "shop-2", 30, false),
            workerRow("a", "shop-1", 20, false)))
        .thenReturn(List.of(workerRow("c", "shop-1", 40, true)));

    HrAccountNotificationsResponse r = service.listForAccount(USER, 3);

    assertEquals(2, r.getUnread());
    // unread first (newest first within them), then the read row even though it is newer
    assertEquals(List.of("b", "a", "c"), r.getItems().stream().map(i -> i.getId()).toList());
    assertEquals(List.of(false, false, true),
        r.getItems().stream().map(i -> i.isRead()).toList());

    ArgumentCaptor<Query> q = ArgumentCaptor.forClass(Query.class);
    verify(mongoTemplate, times(2)).find(q.capture(), eq(HrNotification.class));
    Query unread = q.getAllValues().get(0);
    Query read = q.getAllValues().get(1);
    for (Query each : List.of(unread, read)) {
      Document filter = each.getQueryObject();
      assertFalse(filter.containsKey("shopId"));
      assertEquals(USER, filter.get("recipientUserId"));
      assertEquals(NotificationAudience.USER, filter.get("audience"));
      // the sort is the recipient_created index order (createdAt desc)
      assertEquals(-1, each.getSortObject().get("createdAt"));
    }
    assertEquals(3, unread.getLimit());
    // the second walk only fills the room the unread rows left
    assertEquals(1, read.getLimit());
    assertEquals(new Document("$ne", USER), unread.getQueryObject().get("readByUserIds"));
    assertEquals(USER, read.getQueryObject().get("readByUserIds"));
    verify(hrAccessService, never()).permissions(any(), any());
  }

  @Test
  void listForAccountSkipsTheReadWalkWhenUnreadRowsFillThePage() {
    when(mongoTemplate.count(any(Query.class), eq(HrNotification.class))).thenReturn(5L);
    when(mongoTemplate.find(any(Query.class), eq(HrNotification.class)))
        .thenReturn(List.of(workerRow("c", "shop-1", 30, false),
            workerRow("b", "shop-1", 20, false), workerRow("a", "shop-1", 10, false)));

    HrAccountNotificationsResponse r = service.listForAccount(USER, 3);

    assertEquals(5, r.getUnread());
    assertEquals(3, r.getItems().size());
    verify(mongoTemplate, times(1)).find(any(Query.class), eq(HrNotification.class));
  }

  @Test
  void listForAccountClampsTheLimitToOnePage() {
    when(mongoTemplate.count(any(Query.class), eq(HrNotification.class))).thenReturn(0L);
    when(mongoTemplate.find(any(Query.class), eq(HrNotification.class))).thenReturn(List.of());

    service.listForAccount(USER, 500);

    ArgumentCaptor<Query> q = ArgumentCaptor.forClass(Query.class);
    verify(mongoTemplate, times(2)).find(q.capture(), eq(HrNotification.class));
    assertEquals(HrNotificationService.PAGE_SIZE, q.getAllValues().get(0).getLimit());
  }

  @Test
  void listForAccountCarriesTheJoinRequestIdAndNoneForAProfileRequest() {
    HrNotification approved = workerRow("a", "shop-1", 20, false);
    approved.setJoinRequestId("jr-1");
    HrNotification profile = workerRow("p", "shop-1", 30, false);
    profile.setType(HrNotificationType.PROFILE_REQUESTED);
    profile.setShopId(null);
    profile.setText("Sri Ganesh Stores asked for your profile details");
    when(mongoTemplate.count(any(Query.class), eq(HrNotification.class))).thenReturn(2L);
    when(mongoTemplate.find(any(Query.class), eq(HrNotification.class)))
        .thenReturn(List.of(profile, approved));

    HrAccountNotificationsResponse r = service.listForAccount(USER, 2);

    assertEquals(2, r.getItems().size());
    assertEquals(HrNotificationType.PROFILE_REQUESTED, r.getItems().get(0).getType());
    assertNull(r.getItems().get(0).getJoinRequestId());
    assertEquals(HrNotificationType.JOIN_REQUEST_APPROVED, r.getItems().get(1).getType());
    assertEquals("jr-1", r.getItems().get(1).getJoinRequestId());
    assertNull(r.getItems().get(1).getLeaveRequestId());
    // the page is full of unread rows: one query, no permission lookup, no shop in the filter
    verify(mongoTemplate, times(1)).find(any(Query.class), eq(HrNotification.class));
    verify(hrAccessService, never()).permissions(any(), any());
  }

  // ---- writes --------------------------------------------------------------------------------

  @Test
  void writesCarryTheAudience() {
    service.notifyApprovers(SHOP, HrNotificationType.LEAVE_REQUESTED, "text", "l1");
    ArgumentCaptor<HrNotification> n = ArgumentCaptor.forClass(HrNotification.class);
    verify(mongoTemplate).insert(n.capture());
    assertEquals(NotificationAudience.APPROVERS, n.getValue().getAudience());
    assertEquals("l1", n.getValue().getLeaveRequestId());
    assertNull(n.getValue().getJoinRequestId());
    assertFalse(n.getValue().getText().isEmpty());
  }

  @Test
  void aJoinRequestReceivedRowGoesToTheShopsApproversWithTheJoinRequestId() {
    service.notifyApprovers(SHOP, HrNotificationType.JOIN_REQUEST_RECEIVED,
        WorkerNotificationTexts.joinRequestReceived("Priya"), "jr-1");

    ArgumentCaptor<HrNotification> n = ArgumentCaptor.forClass(HrNotification.class);
    verify(mongoTemplate).insert(n.capture());
    HrNotification written = n.getValue();
    assertEquals(SHOP, written.getShopId());
    assertEquals(NotificationAudience.APPROVERS, written.getAudience());
    assertNull(written.getRecipientUserId());
    assertEquals(HrNotificationType.JOIN_REQUEST_RECEIVED, written.getType());
    assertEquals("jr-1", written.getJoinRequestId());
    assertNull(written.getLeaveRequestId());
    assertEquals("Priya asked to join", written.getText());
  }

  @Test
  void aWorkerRowIsAddressedToTheAccountNotAShop() {
    service.notifyUser(USER, HrNotificationType.JOIN_REQUEST_APPROVED,
        WorkerNotificationTexts.joinRequestApproved("Sri Ganesh Stores"), "jr-1");

    ArgumentCaptor<HrNotification> n = ArgumentCaptor.forClass(HrNotification.class);
    verify(mongoTemplate).insert(n.capture());
    HrNotification written = n.getValue();
    assertNull(written.getShopId());
    assertEquals(NotificationAudience.USER, written.getAudience());
    assertEquals(USER, written.getRecipientUserId());
    assertEquals(HrNotificationType.JOIN_REQUEST_APPROVED, written.getType());
    assertEquals("jr-1", written.getJoinRequestId());
    assertNull(written.getLeaveRequestId());
    assertEquals("Sri Ganesh Stores accepted you", written.getText());
    assertTrue(written.getReadByUserIds().isEmpty());
  }

  @Test
  void aProfileRequestedRowHasNoJoinRequest() {
    service.notifyUser(USER, HrNotificationType.PROFILE_REQUESTED,
        WorkerNotificationTexts.profileRequested("Sri Ganesh Stores"), null);

    ArgumentCaptor<HrNotification> n = ArgumentCaptor.forClass(HrNotification.class);
    verify(mongoTemplate).insert(n.capture());
    assertEquals(HrNotificationType.PROFILE_REQUESTED, n.getValue().getType());
    assertNull(n.getValue().getJoinRequestId());
    assertEquals("Sri Ganesh Stores asked for your profile details", n.getValue().getText());
  }

  @Test
  void theAccountWriteRefusesLeaveTypesAndSkipsAMissingRecipient() {
    assertThrows(IllegalArgumentException.class,
        () -> service.notifyUser(USER, HrNotificationType.LEAVE_APPROVED, "text", null));
    service.notifyUser(null, HrNotificationType.JOIN_REQUEST_APPROVED, "text", "jr-1");
    verify(mongoTemplate, never()).insert(any(HrNotification.class));
  }

  @Test
  void aFailedWriteIsDroppedNotThrown() {
    when(mongoTemplate.insert(any(HrNotification.class))).thenThrow(new RuntimeException("down"));
    service.notifyUser(USER, HrNotificationType.JOIN_REQUEST_REJECTED, "text", "jr-1");
  }

  // ---- texts: names, shop name and reason only (R10.3) ---------------------------------------

  @Test
  void workerTextsCarryNamesShopAndReasonAndNeverPhoneOrEmail() {
    String shop = "Sri Ganesh Stores";
    List<String> texts = List.of(
        WorkerNotificationTexts.joinRequestReceived("Priya"),
        WorkerNotificationTexts.joinRequestApproved(shop),
        WorkerNotificationTexts.joinRequestRejected(shop, "No vacancy"),
        WorkerNotificationTexts.joinRequestRejected(shop, null),
        WorkerNotificationTexts.joinRequestRejected(shop, "  Not our worker  "),
        WorkerNotificationTexts.joinRequestRejected(shop, "   "),
        WorkerNotificationTexts.profileRequested(shop));

    for (String text : texts) {
      assertFalse(text.contains(PHONE), text);
      assertFalse(text.contains(EMAIL), text);
      assertFalse(text.contains("@"), text);
      assertFalse(text.matches(".*\\d{10}.*"), text);
    }
    assertEquals("Priya asked to join", texts.get(0));
    assertEquals("Sri Ganesh Stores accepted you", texts.get(1));
    assertEquals("Sri Ganesh Stores did not accept your request: No vacancy", texts.get(2));
    assertEquals("Sri Ganesh Stores did not accept your request", texts.get(3));
    assertEquals("Sri Ganesh Stores did not accept your request: Not our worker", texts.get(4));
    assertEquals("Sri Ganesh Stores did not accept your request", texts.get(5));
    for (String text : texts.subList(1, texts.size())) {
      assertTrue(text.contains(shop), text);
    }
  }
}
