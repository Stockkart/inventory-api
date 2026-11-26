package com.inventory.hr.service;

import static com.inventory.hr.HrTestFixtures.SHOP;
import static com.inventory.hr.HrTestFixtures.employee;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.inventory.hr.domain.model.Employee;
import com.inventory.hr.domain.model.WorkerJoinRequest;
import com.inventory.hr.domain.model.enums.EmployeeStatus;
import com.inventory.hr.domain.model.enums.WorkerJoinStatus;
import com.inventory.hr.domain.repository.WorkerJoinRequestRepository;
import com.mongodb.client.result.UpdateResult;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class WorkerExitJobTest {

  /** 18:31 UTC is already 00:01 of the next day in Asia/Kolkata. */
  private static final Instant NOW = Instant.parse("2025-04-09T18:31:00Z");
  private static final String TODAY_IST = "2025-04-10";

  @Mock private MongoTemplate mongoTemplate;
  @Mock private WorkerJoinRequestRepository joinRequestRepository;
  @Mock private WorkerMembershipEnder membershipEnder;
  @InjectMocks private WorkerExitJob job;

  @BeforeEach
  void setUp() {
    job.setClock(Clock.fixed(NOW, ZoneOffset.UTC));
    ReflectionTestUtils.setField(job, "enabled", true);
  }

  private static Employee due(String id) {
    Employee e = employee(id);
    e.setUserId("user-" + id);
    e.setStatus(EmployeeStatus.EXITED);
    e.setExitDate("2025-04-09");
    return e;
  }

  private static WorkerJoinRequest approving(String id, Instant since) {
    WorkerJoinRequest r = new WorkerJoinRequest();
    r.setId(id);
    r.setShopId(SHOP);
    r.setUserId("user-" + id);
    r.setStatus(WorkerJoinStatus.APPROVING);
    r.setUpdatedAt(since);
    return r;
  }

  // ---- due exits ------------------------------------------------------------------------------

  @Test
  void theDueReadRepeatsTheIndexPartialFilterExactly() {
    Document where = WorkerExitJob.dueQuery(LocalDate.parse(TODAY_IST)).getQueryObject();

    // the two predicates of exit_membership_pending's partialFilterExpression (HrIndexMigration):
    // $type 2 (string) and an explicit null, not $exists / $ne, or Mongo scans the collection
    Document filter = new Document("userId", new Document("$type", 2))
        .append("membershipEndedAt", null);
    for (String key : filter.keySet()) {
      assertTrue(where.containsKey(key), key + " must be in the query");
      assertEquals(filter.get(key), where.get(key), key);
    }
    // the indexed keys
    assertEquals(EmployeeStatus.EXITED.name(), where.get("status"));
    assertEquals(new Document("$lte", TODAY_IST), where.get("exitDate"));
    assertEquals(4, where.size(), "nothing else, so the planner can use the partial index");
  }

  @Test
  void todayIsTakenInAsiaKolkata() {
    when(mongoTemplate.find(any(Query.class), eq(Employee.class))).thenReturn(List.of());

    job.endDue(NOW);

    ArgumentCaptor<Query> query = ArgumentCaptor.forClass(Query.class);
    verify(mongoTemplate).find(query.capture(), eq(Employee.class));
    assertEquals(new Document("$lte", TODAY_IST), query.getValue().getQueryObject().get("exitDate"));
  }

  @Test
  void everyDueEmployeeIsHandedToTheEnder() {
    when(mongoTemplate.find(any(Query.class), eq(Employee.class)))
        .thenReturn(List.of(due("e1"), due("e2")));

    assertEquals(2, job.endDue(NOW));

    verify(membershipEnder).onExit(due("e1"));
    verify(membershipEnder).onExit(due("e2"));
  }

  @Test
  void aTickWithNothingDueTouchesNothing() {
    // idempotent: once the ledger is written the row leaves the read, and the ender is not called
    when(mongoTemplate.find(any(Query.class), eq(Employee.class))).thenReturn(List.of());
    when(joinRequestRepository.findByStatusAndUpdatedAtBefore(any(), any())).thenReturn(List.of());

    job.run();
    job.run();

    verifyNoInteractions(membershipEnder);
    verify(mongoTemplate, never()).updateFirst(any(Query.class), any(Update.class), any(Class.class));
  }

  @Test
  void oneFailureDoesNotStopTheOthersAndIsLeftForTheNextTick() {
    when(mongoTemplate.find(any(Query.class), eq(Employee.class)))
        .thenReturn(List.of(due("e1"), due("e2")));
    doThrow(new DataAccessResourceFailureException("down")).when(membershipEnder).onExit(due("e1"));

    assertEquals(1, job.endDue(NOW));

    // e1's ledger is still open, so the next read returns it again; e2 was done
    verify(membershipEnder).onExit(due("e2"));
  }

  @Test
  void aFailedReadIsLoggedNotThrownAndTheSweepStillRuns() {
    when(mongoTemplate.find(any(Query.class), eq(Employee.class)))
        .thenThrow(new DataAccessResourceFailureException("down"));
    when(joinRequestRepository.findByStatusAndUpdatedAtBefore(any(), any())).thenReturn(List.of());

    assertDoesNotThrow(() -> job.run());

    verify(joinRequestRepository).findByStatusAndUpdatedAtBefore(WorkerJoinStatus.APPROVING,
        NOW.minus(WorkerExitJob.APPROVING_GRACE));
  }

  // ---- APPROVING sweep ------------------------------------------------------------------------

  @Test
  void aRequestStuckInApprovingForOverFiveMinutesGoesBackToPending() {
    Instant since = NOW.minusSeconds(6 * 60);
    when(joinRequestRepository.findByStatusAndUpdatedAtBefore(WorkerJoinStatus.APPROVING,
        NOW.minusSeconds(5 * 60))).thenReturn(List.of(approving("r1", since)));
    when(mongoTemplate.updateFirst(any(Query.class), any(Update.class), eq(WorkerJoinRequest.class)))
        .thenReturn(UpdateResult.acknowledged(1, 1L, null));

    assertEquals(1, job.resetStuckApprovals(NOW));

    ArgumentCaptor<Query> query = ArgumentCaptor.forClass(Query.class);
    ArgumentCaptor<Update> update = ArgumentCaptor.forClass(Update.class);
    verify(mongoTemplate).updateFirst(query.capture(), update.capture(),
        eq(WorkerJoinRequest.class));
    Document where = query.getValue().getQueryObject();
    assertEquals("r1", where.get("_id"));
    assertEquals("APPROVING", where.get("status"), "conditional: a commit meanwhile wins");
    Document set = update.getValue().getUpdateObject().get("$set", Document.class);
    assertEquals("PENDING", set.get("status"));
    assertEquals(NOW, set.get("updatedAt"));
    assertEquals(2, set.size(), "snapshot, decision fields and employee are untouched");
  }

  @Test
  void aRequestThatCommittedMeanwhileIsNotCountedAsReset() {
    when(joinRequestRepository.findByStatusAndUpdatedAtBefore(any(), any()))
        .thenReturn(List.of(approving("r1", NOW.minusSeconds(600))));
    when(mongoTemplate.updateFirst(any(Query.class), any(Update.class), eq(WorkerJoinRequest.class)))
        .thenReturn(UpdateResult.acknowledged(0, 0L, null));

    assertEquals(0, job.resetStuckApprovals(NOW));
  }

  @Test
  void oneFailedResetDoesNotStopTheOthers() {
    when(joinRequestRepository.findByStatusAndUpdatedAtBefore(any(), any()))
        .thenReturn(List.of(approving("r1", NOW.minusSeconds(600)),
            approving("r2", NOW.minusSeconds(600))));
    when(mongoTemplate.updateFirst(any(Query.class), any(Update.class), eq(WorkerJoinRequest.class)))
        .thenThrow(new DataAccessResourceFailureException("down"))
        .thenReturn(UpdateResult.acknowledged(1, 1L, null));

    assertEquals(1, job.resetStuckApprovals(NOW));
  }

  @Test
  void aFailedSweepIsLoggedNotThrown() {
    when(mongoTemplate.find(any(Query.class), eq(Employee.class))).thenReturn(List.of());
    when(joinRequestRepository.findByStatusAndUpdatedAtBefore(any(), any()))
        .thenThrow(new DataAccessResourceFailureException("down"));

    assertDoesNotThrow(() -> job.run());
  }

  // ---- switch ---------------------------------------------------------------------------------

  @Test
  void theJobCanBeTurnedOff() {
    ReflectionTestUtils.setField(job, "enabled", false);

    job.run();

    verifyNoInteractions(mongoTemplate, joinRequestRepository, membershipEnder);
  }
}
