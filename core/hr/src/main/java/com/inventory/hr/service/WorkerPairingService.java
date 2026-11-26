package com.inventory.hr.service;

import com.inventory.common.constants.ErrorCode;
import com.inventory.common.exception.AuthenticationException;
import com.inventory.common.exception.BaseException;
import com.inventory.common.exception.ValidationException;
import com.inventory.hr.domain.model.Employee;
import com.inventory.hr.domain.model.enums.EmployeeStatus;
import com.inventory.hr.domain.repository.EmployeeRepository;
import com.inventory.hr.rest.dto.request.ClaimPairingRequest;
import com.inventory.hr.rest.dto.request.CompletePairingRequest;
import com.inventory.hr.rest.dto.response.ClaimPairingResponse;
import com.inventory.hr.rest.dto.response.PairingBootstrapResponse;
import com.inventory.user.api.WorkerAccounts;
import java.util.Comparator;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Pairing for a signed-in worker (worker-app R7.2–R7.5, R9.1; design DD4): the only pairing
 * handshake.
 *
 * <p>{@link #claim}, {@link #complete} and {@link #abandon} are the HR-module three steps with one
 * rule added before any key is registered: the caller must have a linked ACTIVE employee ({@code
 * HR_WORKER_NOT_ACTIVE} otherwise) and the code must be for that employee. The rule itself, the
 * attempt counting and the {@code PAIRING_CODE_REFUSED} audit row live in {@link
 * DevicePairingService}; this class only decides <em>who</em> is asking and hands that employee
 * down as {@code expectedEmployee}. The names in the claim answer are the caller's own (R7.2), so
 * the "Yes, this is me" screen can never show someone else's.
 *
 * <p>Not here: the per-installation lockout of R2.5 ({@code AppRateLimiter#guardCodeAttempt}) is
 * applied by the controller around {@link #claim} and {@link #complete}; and the request-shape
 * checks (400s) are the controller's too.
 */
@Slf4j
@Service
public class WorkerPairingService {

  @Autowired private DevicePairingService pairingService;

  @Autowired private EmployeeRepository employeeRepository;

  @Autowired private WorkerAccounts workerAccounts;

  // ---- handshake with a login (R7.2–R7.4) -----------------------------------------------------

  /**
   * Step one with the caller's identity: the code must be for the caller's own employee (R7.3), a
   * self-pairing code only for the account it was made from (R7.4). The answer's {@code
   * employeeName} and {@code shopName} are the caller's own (R7.2).
   *
   * @throws BaseException {@code HR_WORKER_NOT_ACTIVE} when the caller has no linked ACTIVE
   *     employee; {@code HR_PAIRING_WRONG_EMPLOYEE} and the HR-module refusals from {@link
   *     DevicePairingService#claim}
   */
  public ClaimPairingResponse claim(String userId, ClaimPairingRequest body) {
    requireBody(body);
    Employee mine = linkedActiveEmployee(userId);
    ClaimPairingResponse answer = pairingService.claim(body.getCode(), body.getInstallationId(),
        body.getOs(), body.getOsVersion(), body.getPhoneModel(), body.getAppVersion(), mine);
    // the rule guarantees the code's employee is the caller's; say so in the caller's own words
    answer.setEmployeeName(mine.getName());
    answer.setShopName(pairingService.shopName(mine.getShopId()));
    return answer;
  }

  /**
   * Step two, "I agree", with the caller's identity: the claimed code must still be the caller's
   * employee's (R7.3), checked before any key is parsed or any pairing displaced.
   *
   * @throws BaseException {@code HR_WORKER_NOT_ACTIVE} when the caller has no linked ACTIVE
   *     employee; the refusals of {@link DevicePairingService#complete}
   */
  public PairingBootstrapResponse complete(String userId, CompletePairingRequest body) {
    requireBody(body);
    Employee mine = linkedActiveEmployee(userId);
    return pairingService.complete(body.getClaimToken(), body.getInstallationId(),
        body.getPunchPublicKey(), body.getDevicePublicKey(), body.getConsentVersion(),
        body.getAttestationChain(), mine);
  }

  /**
   * "No, this isn't me" or "Don't agree" (R2.6, R2.8) from the signed-in app. The caller must be
   * an active worker like the other two steps, so a staff account without an employee gets the
   * same answer everywhere; the token itself is only a claim token, so the HR-module rule applies:
   * unknown or spent tokens are ignored.
   *
   * @throws BaseException {@code HR_WORKER_NOT_ACTIVE} when the caller has no linked ACTIVE employee
   */
  public void abandon(String userId, String claimToken) {
    if (!StringUtils.hasText(claimToken)) {
      throw new ValidationException("claimToken is required");
    }
    linkedActiveEmployee(userId);
    pairingService.abandon(claimToken);
  }

  // ---- who is asking --------------------------------------------------------------------------

  /**
   * The caller's linked ACTIVE employee (R7.3, R5.1). A WORKER account has at most one; a staff
   * account linked in several shops gets the one in its active shop, else the first by shop id so
   * the answer is the same on every call.
   *
   * @throws BaseException {@code HR_WORKER_NOT_ACTIVE} when there is none
   */
  Employee linkedActiveEmployee(String userId) {
    requireUser(userId);
    List<Employee> linked = employeeRepository.findByUserIdAndStatus(userId, EmployeeStatus.ACTIVE);
    if (linked == null || linked.isEmpty()) {
      throw new BaseException(ErrorCode.HR_WORKER_NOT_ACTIVE,
          ErrorCode.HR_WORKER_NOT_ACTIVE.getMessage());
    }
    if (linked.size() == 1) {
      return linked.get(0);
    }
    String activeShop = workerAccounts.account(userId)
        .map(WorkerAccounts.AccountView::shopId).orElse(null);
    return linked.stream()
        .filter(e -> activeShop != null && activeShop.equals(e.getShopId()))
        .findFirst()
        .orElseGet(() -> linked.stream()
            .min(Comparator.comparing(Employee::getShopId,
                Comparator.nullsLast(Comparator.naturalOrder())))
            .orElseThrow());
  }

  // ---- helpers --------------------------------------------------------------------------------

  private static void requireBody(Object body) {
    if (body == null) {
      throw new ValidationException("A request body is required");
    }
  }

  private static void requireUser(String userId) {
    if (!StringUtils.hasText(userId)) {
      throw new AuthenticationException(ErrorCode.UNAUTHORIZED, "User not authenticated");
    }
  }
}
