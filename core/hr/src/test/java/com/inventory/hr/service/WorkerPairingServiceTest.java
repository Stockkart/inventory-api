package com.inventory.hr.service;

import static com.inventory.hr.HrTestFixtures.SHOP;
import static com.inventory.hr.HrTestFixtures.USER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.inventory.common.constants.ErrorCode;
import com.inventory.common.exception.AuthenticationException;
import com.inventory.common.exception.BaseException;
import com.inventory.common.exception.ValidationException;
import com.inventory.hr.HrTestFixtures;
import com.inventory.hr.domain.model.Employee;
import com.inventory.hr.domain.model.enums.DeviceOs;
import com.inventory.hr.domain.model.enums.EmployeeStatus;
import com.inventory.hr.domain.repository.EmployeeRepository;
import com.inventory.hr.rest.dto.request.ClaimPairingRequest;
import com.inventory.hr.rest.dto.request.CompletePairingRequest;
import com.inventory.hr.rest.dto.response.ClaimPairingResponse;
import com.inventory.hr.rest.dto.response.PairingBootstrapResponse;
import com.inventory.user.api.WorkerAccounts;
import com.inventory.user.api.WorkerAccounts.AccountView;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * {@link WorkerPairingService} by example (worker-app R7.2–R7.5): the only pairing handshake (DD4).
 * The rule itself is {@code DevicePairingServiceTest}'s; here the service is a mock and the test
 * checks that the caller's own employee is what gets handed down. Properties are task 6.6.
 */
@ExtendWith(MockitoExtension.class)
class WorkerPairingServiceTest {

  private static final String EMPLOYEE = "emp-1";
  private static final String INSTALL = "7c9e6679-7425-40de-944b-e07fc1f90ae7";

  @Mock private DevicePairingService pairingService;
  @Mock private EmployeeRepository employeeRepository;
  @Mock private WorkerAccounts workerAccounts;
  @InjectMocks private WorkerPairingService service;

  private Employee mine;

  @BeforeEach
  void setUp() {
    mine = HrTestFixtures.employee(EMPLOYEE);
    mine.setUserId(USER);
    lenient().when(pairingService.shopName(SHOP)).thenReturn("Sri Ganesh Stores");
  }

  private void linked(Employee... employees) {
    when(employeeRepository.findByUserIdAndStatus(USER, EmployeeStatus.ACTIVE))
        .thenReturn(List.of(employees));
  }

  private static ClaimPairingRequest claimBody() {
    ClaimPairingRequest body = new ClaimPairingRequest();
    body.setCode("ABCD2345");
    body.setInstallationId(INSTALL);
    body.setOs(DeviceOs.ANDROID);
    body.setOsVersion("14");
    body.setPhoneModel("Redmi Note 11");
    body.setAppVersion("2.0.0");
    return body;
  }

  private static CompletePairingRequest completeBody() {
    CompletePairingRequest body = new CompletePairingRequest();
    body.setClaimToken("claim-token");
    body.setInstallationId(INSTALL);
    body.setPunchPublicKey("punch-key");
    body.setDevicePublicKey("device-key");
    body.setConsentVersion(1);
    body.setAttestationChain(List.of("leaf", "root"));
    return body;
  }

  private static BaseException assertCode(ErrorCode code, Runnable call) {
    BaseException e = assertThrows(BaseException.class, call::run);
    assertEquals(code, e.getErrorCode());
    return e;
  }

  // ---- claim / complete / abandon --------------------------------------------------------------

  @Nested
  class Handshake {

    @Test
    void claimDelegatesWithTheLinkedEmployeeAndAnswersTheCallersOwnNames() {
      linked(mine);
      ClaimPairingRequest body = claimBody();
      when(pairingService.claim("ABCD2345", INSTALL, DeviceOs.ANDROID, "14", "Redmi Note 11",
          "2.0.0", mine)).thenReturn(new ClaimPairingResponse("token", "stale shop",
              "stale name", null));

      ClaimPairingResponse answer = service.claim(USER, body);

      assertEquals("token", answer.getClaimToken());
      assertEquals("Ravi Kumar", answer.getEmployeeName());
      assertEquals("Sri Ganesh Stores", answer.getShopName());
      verify(pairingService).claim(anyString(), anyString(), any(), anyString(), anyString(),
          anyString(), same(mine));
    }

    @Test
    void completeDelegatesWithTheLinkedEmployee() {
      linked(mine);
      PairingBootstrapResponse bootstrap = new PairingBootstrapResponse("pairing-1", SHOP, EMPLOYEE,
          "Ravi Kumar", "Sri Ganesh Stores", null, 1);
      when(pairingService.complete("claim-token", INSTALL, "punch-key", "device-key", 1,
          List.of("leaf", "root"), mine)).thenReturn(bootstrap);

      assertSame(bootstrap, service.complete(USER, completeBody()));
    }

    @Test
    void abandonDelegatesOnceTheCallerIsAnActiveWorker() {
      linked(mine);

      service.abandon(USER, "claim-token");

      verify(pairingService).abandon("claim-token");
    }

    @Test
    void noActiveEmployeeIsRefusedBeforeAnyHandshakeStep() {
      when(employeeRepository.findByUserIdAndStatus(USER, EmployeeStatus.ACTIVE))
          .thenReturn(List.of());

      assertCode(ErrorCode.HR_WORKER_NOT_ACTIVE, () -> service.claim(USER, claimBody()));
      assertCode(ErrorCode.HR_WORKER_NOT_ACTIVE, () -> service.complete(USER, completeBody()));
      assertCode(ErrorCode.HR_WORKER_NOT_ACTIVE, () -> service.abandon(USER, "claim-token"));
      verifyNoInteractions(pairingService);
    }

    @Test
    void aStaffAccountLinkedInSeveralShopsPairsAsTheEmployeeOfItsActiveShop() {
      Employee elsewhere = HrTestFixtures.employee("emp-9");
      elsewhere.setShopId("shop-9");
      elsewhere.setName("Ravi (other shop)");
      elsewhere.setUserId(USER);
      linked(elsewhere, mine);
      when(workerAccounts.account(USER)).thenReturn(Optional.of(
          new AccountView(USER, "Ravi Kumar", "ravi@example.com", "9876543210", "MANAGER", SHOP,
              false)));
      when(pairingService.claim(anyString(), anyString(), any(), anyString(), anyString(),
          anyString(), same(mine))).thenReturn(new ClaimPairingResponse("token", null, null, null));

      ClaimPairingResponse answer = service.claim(USER, claimBody());

      assertEquals("Ravi Kumar", answer.getEmployeeName());
      assertEquals("Sri Ganesh Stores", answer.getShopName());
    }

    @Test
    void shapeAndIdentityAreRequired() {
      assertThrows(ValidationException.class, () -> service.claim(USER, null));
      assertThrows(ValidationException.class, () -> service.complete(USER, null));
      assertThrows(ValidationException.class, () -> service.abandon(USER, " "));
      assertThrows(AuthenticationException.class, () -> service.claim(null, claimBody()));
      verifyNoInteractions(pairingService, employeeRepository);
    }
  }
}
