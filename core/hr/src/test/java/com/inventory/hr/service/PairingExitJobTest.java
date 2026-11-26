package com.inventory.hr.service;

import static com.inventory.hr.HrTestFixtures.SHOP;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.inventory.hr.domain.model.DevicePairing;
import com.inventory.hr.domain.model.enums.PairingStatus;
import com.inventory.hr.domain.model.enums.UnpairReason;
import com.inventory.hr.domain.repository.DevicePairingRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class PairingExitJobTest {

  private static final Instant NOW = Instant.parse("2025-04-09T18:31:00Z");

  @Mock private DevicePairingRepository pairingRepository;
  @Mock private DevicePairingService pairingService;
  @InjectMocks private PairingExitJob job;

  @BeforeEach
  void setUp() {
    job.setClock(Clock.fixed(NOW, ZoneOffset.UTC));
    ReflectionTestUtils.setField(job, "enabled", true);
  }

  private static DevicePairing due(String id) {
    DevicePairing p = new DevicePairing();
    p.setId(id);
    p.setShopId(SHOP);
    p.setEmployeeId("emp-" + id);
    p.setStatus(PairingStatus.ACTIVE);
    p.setScheduledUnpairAt(Instant.parse("2025-04-09T18:30:00Z"));
    return p;
  }

  @Test
  void everyDuePairingIsUnpairedAsEmployeeExitedByTheSystem() {
    when(pairingRepository.findByStatusAndScheduledUnpairAtLessThanEqual(PairingStatus.ACTIVE, NOW))
        .thenReturn(List.of(due("p1"), due("p2")));
    when(pairingService.unpair(any(), eq(UnpairReason.EMPLOYEE_EXITED), eq("system")))
        .thenAnswer(i -> Optional.of(due(i.getArgument(0))));

    job.run();

    verify(pairingService).unpair("p1", UnpairReason.EMPLOYEE_EXITED, "system");
    verify(pairingService).unpair("p2", UnpairReason.EMPLOYEE_EXITED, "system");
  }

  @Test
  void theReadIsOnlyForActivePairingsDueByNow() {
    when(pairingRepository.findByStatusAndScheduledUnpairAtLessThanEqual(PairingStatus.ACTIVE, NOW))
        .thenReturn(List.of());

    assertEquals(0, job.unpairDue(NOW));

    verify(pairingRepository).findByStatusAndScheduledUnpairAtLessThanEqual(PairingStatus.ACTIVE,
        NOW);
    verifyNoInteractions(pairingService);
  }

  @Test
  void aPairingAlreadyUnpairedByAnotherRouteIsNotCounted() {
    // idempotent: the conditional update inside unpair() matched nothing
    when(pairingRepository.findByStatusAndScheduledUnpairAtLessThanEqual(PairingStatus.ACTIVE, NOW))
        .thenReturn(List.of(due("p1")));
    when(pairingService.unpair("p1", UnpairReason.EMPLOYEE_EXITED, "system"))
        .thenReturn(Optional.empty());

    assertEquals(0, job.unpairDue(NOW));
  }

  @Test
  void oneFailureDoesNotStopTheOthersAndIsLeftForTheNextTick() {
    when(pairingRepository.findByStatusAndScheduledUnpairAtLessThanEqual(PairingStatus.ACTIVE, NOW))
        .thenReturn(List.of(due("p1"), due("p2")));
    when(pairingService.unpair("p1", UnpairReason.EMPLOYEE_EXITED, "system"))
        .thenThrow(new DataAccessResourceFailureException("down"));
    when(pairingService.unpair("p2", UnpairReason.EMPLOYEE_EXITED, "system"))
        .thenReturn(Optional.of(due("p2")));

    assertEquals(1, job.unpairDue(NOW));

    // the failed one keeps its schedule, so the next read returns it again
    verify(pairingService).unpair("p2", UnpairReason.EMPLOYEE_EXITED, "system");
  }

  @Test
  void aFailedReadIsLoggedNotThrown() {
    when(pairingRepository.findByStatusAndScheduledUnpairAtLessThanEqual(any(), any()))
        .thenThrow(new DataAccessResourceFailureException("down"));

    assertDoesNotThrow(() -> job.run());
  }

  @Test
  void theJobCanBeTurnedOff() {
    ReflectionTestUtils.setField(job, "enabled", false);

    job.run();

    verify(pairingRepository, never()).findByStatusAndScheduledUnpairAtLessThanEqual(any(), any());
  }
}
