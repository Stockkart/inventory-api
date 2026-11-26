package com.inventory.hr.service;

import static com.inventory.hr.HrTestFixtures.SHOP;
import static com.inventory.hr.HrTestFixtures.employee;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.inventory.hr.domain.model.Employee;
import com.inventory.hr.domain.model.enums.EmployeeStatus;
import com.inventory.user.api.WorkerAccounts;
import com.inventory.user.api.WorkerAccounts.AccountView;
import com.mongodb.client.result.UpdateResult;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

@ExtendWith(MockitoExtension.class)
class WorkerMembershipEnderImplTest {

  private static final Instant NOW = Instant.parse("2025-04-09T18:31:00Z");
  private static final String WORKER = "user-worker";

  @Mock private MongoTemplate mongoTemplate;
  @Mock private WorkerAccounts workerAccounts;
  @InjectMocks private WorkerMembershipEnderImpl ender;

  @BeforeEach
  void setUp() {
    ender.setClock(Clock.fixed(NOW, ZoneOffset.UTC));
  }

  private static AccountView account(String userId, String role, boolean worker) {
    return new AccountView(userId, "Someone", userId + "@example.com", "9876543210", role, SHOP,
        worker);
  }

  private void accountIs(String userId, boolean worker) {
    when(workerAccounts.account(userId))
        .thenReturn(Optional.of(account(userId, worker ? "WORKER" : "MANAGER", worker)));
  }

  private static Employee exited(String userId) {
    Employee e = employee("emp-1");
    e.setUserId(userId);
    e.setStatus(EmployeeStatus.EXITED);
    e.setExitDate("2025-04-09");
    e.setMembershipEndedAt(null);
    return e;
  }

  private void ledgerCloses(long modified) {
    when(mongoTemplate.updateFirst(any(Query.class), any(Update.class), eq(Employee.class)))
        .thenReturn(UpdateResult.acknowledged(modified, modified, null));
  }

  @Test
  void aWorkerAccountIsReleasedFromTheShopAndTheLedgerIsClosed() {
    accountIs(WORKER, true);
    ledgerCloses(1);

    assertTrue(ender.end(exited(WORKER)));

    verify(workerAccounts).releaseShop(SHOP, WORKER);
    ArgumentCaptor<Query> query = ArgumentCaptor.forClass(Query.class);
    ArgumentCaptor<Update> update = ArgumentCaptor.forClass(Update.class);
    verify(mongoTemplate).updateFirst(query.capture(), update.capture(), eq(Employee.class));
    Document where = query.getValue().getQueryObject();
    assertEquals("emp-1", where.get("_id"));
    assertTrue(where.containsKey("membershipEndedAt"), "conditional on the ledger being open");
    assertEquals(null, where.get("membershipEndedAt"));
    Document set = update.getValue().getUpdateObject().get("$set", Document.class);
    assertEquals(NOW, set.get("membershipEndedAt"));
    assertEquals(1, set.size(), "userId and every other field stay as they are");
  }

  @Test
  void theAccountIsReleasedBeforeTheLedgerIsWrittenSoAFailureBetweenThemIsRetried() {
    accountIs(WORKER, true);
    ledgerCloses(1);

    ender.onExit(exited(WORKER));

    InOrder order = inOrder(workerAccounts, mongoTemplate);
    order.verify(workerAccounts).releaseShop(SHOP, WORKER);
    order.verify(mongoTemplate).updateFirst(any(Query.class), any(Update.class),
        eq(Employee.class));
  }

  @Test
  void aStaffAccountKeepsItsMembershipButTheLedgerStillCloses() {
    accountIs("user-manager", false);
    ledgerCloses(1);

    assertTrue(ender.end(exited("user-manager")));

    verify(workerAccounts, never()).releaseShop(any(), any());
    verify(mongoTemplate).updateFirst(any(Query.class), any(Update.class), eq(Employee.class));
  }

  @Test
  void anAccountThatNoLongerExistsIsNotReleasedButTheLedgerStillCloses() {
    when(workerAccounts.account("user-gone")).thenReturn(Optional.empty());
    ledgerCloses(1);

    assertTrue(ender.end(exited("user-gone")));

    verify(workerAccounts, never()).releaseShop(any(), any());
  }

  @Test
  void anExitAlreadyEndedIsANoOp() {
    Employee done = exited(WORKER);
    done.setMembershipEndedAt(Instant.parse("2025-04-08T18:30:00Z"));

    assertFalse(ender.end(done));

    verifyNoInteractions(workerAccounts, mongoTemplate);
  }

  @Test
  void anEmployeeWithoutALoginHasNothingToEnd() {
    assertFalse(ender.end(exited(null)));

    verifyNoInteractions(workerAccounts, mongoTemplate);
  }

  @Test
  void aLedgerClosedByAnotherRouteMeanwhileIsNotCountedTwice() {
    // the exit save and the job raced: the conditional update matched nothing
    accountIs(WORKER, true);
    ledgerCloses(0);

    assertFalse(ender.end(exited(WORKER)));

    // releaseShop is conditional on the user side, so the repeat was harmless
    verify(workerAccounts).releaseShop(SHOP, WORKER);
  }
}
