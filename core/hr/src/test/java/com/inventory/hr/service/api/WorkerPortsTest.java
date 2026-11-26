package com.inventory.hr.service.api;

import static com.inventory.hr.HrTestFixtures.SHOP;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.inventory.hr.domain.model.Employee;
import com.inventory.hr.domain.model.WorkerJoinRequest;
import com.inventory.hr.domain.model.enums.HrPermission;
import com.inventory.hr.domain.model.enums.WorkerJoinStatus;
import com.inventory.hr.domain.repository.EmployeeRepository;
import com.inventory.hr.domain.repository.WorkerJoinRequestRepository;
import com.inventory.hr.service.HrAccessService;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/** The two user-module ports HR implements (worker-app R2.5, R11.5). */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class WorkerPortsTest {

  @Mock private EmployeeRepository employeeRepository;
  @Mock private WorkerJoinRequestRepository joinRequestRepository;
  @Mock private HrAccessService hrAccessService;
  @InjectMocks private HrLinkedEmployeePortImpl linkedEmployeePort;
  @InjectMocks private WorkerPhotoAccessPortImpl photoAccessPort;

  private static WorkerJoinRequest request(String shopId, WorkerJoinStatus status,
      Instant decidedAt) {
    WorkerJoinRequest r = new WorkerJoinRequest();
    r.setShopId(shopId);
    r.setUserId("worker-1");
    r.setStatus(status);
    r.setDecidedAt(decidedAt);
    return r;
  }

  @Test
  void linkedEmployeeCodeIsTheShopsEmployeeOfThatLoginOrNull() {
    Employee e = new Employee();
    e.setCode("EMP-014");
    when(employeeRepository.findByShopIdAndUserId(SHOP, "worker-1")).thenReturn(Optional.of(e));
    when(employeeRepository.findByShopIdAndUserId(SHOP, "nobody")).thenReturn(Optional.empty());

    assertEquals("EMP-014", linkedEmployeePort.linkedEmployeeCode(SHOP, "worker-1"));
    assertNull(linkedEmployeePort.linkedEmployeeCode(SHOP, "nobody"));
    assertNull(linkedEmployeePort.linkedEmployeeCode(null, "worker-1"));
    assertNull(linkedEmployeePort.linkedEmployeeCode(SHOP, " "));
  }

  @Test
  void anApproverOfAShopWithAnOpenOrRecentlyRejectedRequestMaySeeThePhoto() {
    Instant now = Instant.now();
    when(joinRequestRepository.findByUserIdAndStatusIn(eq("worker-1"), anyCollection()))
        .thenReturn(List.of(
            request(SHOP, WorkerJoinStatus.PENDING, null),
            request("shop-old", WorkerJoinStatus.REJECTED, now.minus(Duration.ofDays(91))),
            request("shop-recent", WorkerJoinStatus.REJECTED, now.minus(Duration.ofDays(10)))));
    when(hrAccessService.permissions("manager", SHOP))
        .thenReturn(EnumSet.of(HrPermission.ADD_EDIT_EMPLOYEES));
    when(hrAccessService.permissions("cashier", SHOP))
        .thenReturn(EnumSet.of(HrPermission.VIEW_EMPLOYEES));
    when(hrAccessService.permissions("cashier", "shop-recent"))
        .thenReturn(EnumSet.of(HrPermission.ADD_EDIT_EMPLOYEES));
    when(hrAccessService.permissions("stranger", SHOP)).thenThrow(new RuntimeException("none"));
    when(hrAccessService.permissions("stranger", "shop-recent"))
        .thenReturn(EnumSet.noneOf(HrPermission.class));

    assertTrue(photoAccessPort.mayViewRequestPhoto("manager", "worker-1"));
    assertTrue(photoAccessPort.mayViewRequestPhoto("cashier", "worker-1"));
    assertFalse(photoAccessPort.mayViewRequestPhoto("stranger", "worker-1"));
    // the 91-day-old rejection grants nothing: its shop is never asked
    verify(hrAccessService, never()).permissions(eq("manager"), eq("shop-old"));
    verify(hrAccessService, never()).permissions(eq("stranger"), eq("shop-old"));
    assertFalse(photoAccessPort.mayViewRequestPhoto(null, "worker-1"));
    assertFalse(photoAccessPort.mayViewRequestPhoto("manager", null));
  }

  @Test
  void anAccountWithoutRequestsGrantsNobody() {
    when(joinRequestRepository.findByUserIdAndStatusIn(eq("worker-2"), anyCollection()))
        .thenReturn(List.of());

    assertFalse(photoAccessPort.mayViewRequestPhoto("manager", "worker-2"));
    verify(hrAccessService, never()).permissions(eq("manager"), eq(SHOP));
  }
}
