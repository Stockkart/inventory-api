package com.inventory.hr.service;

import com.inventory.common.constants.ErrorCode;
import com.inventory.common.exception.BaseException;
import com.inventory.common.exception.ResourceNotFoundException;
import com.inventory.common.exception.ValidationException;
import com.inventory.hr.api.port.ShopNamePort;
import com.inventory.hr.domain.model.DevicePairing;
import com.inventory.hr.domain.model.Employee;
import com.inventory.hr.domain.model.HrSettings;
import com.inventory.hr.domain.model.PairingCode;
import com.inventory.hr.domain.model.enums.DeviceOs;
import com.inventory.hr.domain.model.enums.EmployeeStatus;
import com.inventory.hr.domain.model.enums.HrAuditAction;
import com.inventory.hr.domain.model.enums.PairingCodeStatus;
import com.inventory.hr.domain.model.enums.PairingStatus;
import com.inventory.hr.domain.model.enums.UnpairReason;
import com.inventory.hr.domain.repository.DevicePairingRepository;
import com.inventory.hr.domain.repository.EmployeeRepository;
import com.inventory.hr.domain.repository.PairingCodeRepository;
import com.inventory.hr.rest.dto.response.AppLocationSettingsResponse;
import com.inventory.hr.rest.dto.response.ClaimPairingResponse;
import com.inventory.hr.rest.dto.response.ConsentResponse;
import com.inventory.hr.rest.dto.response.PairingBootstrapResponse;
import com.inventory.hr.rest.dto.response.PairingCodeResponse;
import com.inventory.hr.rest.dto.response.PairingStateResponse;
import com.inventory.hr.service.HrAuditService.AuditEntry;
import com.inventory.hr.utils.AppSignatures;
import com.inventory.hr.utils.HrDateUtils;
import com.inventory.hr.utils.KeyAttestation;
import com.inventory.hr.utils.PairingCodes;
import com.inventory.hr.utils.constants.HrMetricsConstants;
import com.inventory.hr.utils.constants.HrMetricsConstants.AppPairingEvent;
import com.inventory.hr.utils.constants.HrMetricsConstants.AppPairingFailure;
import com.inventory.metrics.MetricsWrapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Pairing lifecycle of the Attendance app (R2, R3, design DD7): one-time codes, the three-step
 * handshake {@code claim → complete | abandon}, unpairing and the pairing state an employee's page
 * shows.
 *
 * <p>State machine. A code is {@code UNUSED} → {@code CLAIMED} (a phone sent it and sees the
 * names) → {@code USED} (pairing completed; the document is then deleted) or {@code INVALIDATED}
 * ("No" / "Don't agree", or the 11th attempt); a newer code for the same employee moves an
 * {@code UNUSED} one to {@code CANCELLED}. A pairing is {@code ACTIVE} → {@code UNPAIRED} once,
 * by a conditional update, so two routes racing (manager, worker, replacement, exit) unpair it
 * exactly once and write exactly one audit row.
 *
 * <p>Privacy. Codes and claim tokens are stored as SHA-256 hashes only. Audit rows carry the phone
 * model, actor and reason; never a key, an installation id or a coordinate (R2.16, R9.1).
 *
 * <p>Exit (R3.4–R3.6). {@link #scheduleExitUnpair} and {@link #clearExitUnpair} are called from
 * the employee save: an exit date that has arrived unpairs at once; a future one is written to
 * {@code scheduledUnpairAt} for {@link PairingExitJob} to act on at 00:00 Asia/Kolkata of that day.
 *
 * <p>Not here: the per-installation lockout of R2.5 (5 refused codes in 15 minutes) is applied by
 * {@code AppRateLimiter} around {@link #claim}.
 */
@Slf4j
@Service
public class DevicePairingService {

  /** A code is valid for 15 minutes from creation (R2.3). */
  static final Duration CODE_VALIDITY = Duration.ofMinutes(15);

  /** An unpaired pairing record is kept for 90 days (R3.10). */
  static final Duration UNPAIRED_RETENTION = Duration.ofDays(90);

  /** The 11th attempt on one code invalidates it (R9.10). */
  static final int MAX_CODE_ATTEMPTS = 10;

  /** Actor value for a linked employee pairing or unpairing their own phone (R2.13, R3.7). */
  public static final String SELF = "self";

  /** Actor value for the exit-driven unpair (R3.6). */
  public static final String SYSTEM = "system";

  static final String REASON_CODE_INVALID = "The pairing code must be 8 letters or digits";
  static final String REASON_BAD_PUNCH_KEY = "punchPublicKey is not a P-256 public key";
  static final String REASON_BAD_DEVICE_KEY = "devicePublicKey is not a P-256 public key";
  static final String REASON_UNKNOWN_CONSENT = "consentVersion is not a published version";

  private static final int CLAIM_TOKEN_BYTES = 32;
  private static final int CODE_GENERATION_TRIES = 5;
  private static final HexFormat HEX = HexFormat.of();

  @Autowired private PairingCodeRepository codeRepository;

  @Autowired private DevicePairingRepository pairingRepository;

  @Autowired private EmployeeRepository employeeRepository;

  @Autowired private HrSettingsService settingsService;

  @Autowired private ConsentVersions consentVersions;

  @Autowired private HrAuditService auditService;

  @Autowired private MetricsWrapper metrics;

  @Autowired private MongoTemplate mongoTemplate;

  @Autowired private ShopNamePort shopNamePort;

  private final SecureRandom random = new SecureRandom();

  private Clock clock = Clock.systemUTC();

  /** Verifies Android key attestation chains against the Google roots (task 16.4). */
  private KeyAttestation keyAttestation = new KeyAttestation();

  /** Tests pin the clock. */
  void setClock(Clock clock) {
    this.clock = clock;
  }

  /** Tests trust a root they made. */
  void setKeyAttestation(KeyAttestation keyAttestation) {
    this.keyAttestation = keyAttestation;
  }

  // ---- codes ---------------------------------------------------------------------------------

  /**
   * A fresh pairing code for the employee (R2.3, R2.4). The previous {@code UNUSED} code is
   * cancelled; a claimed one is left for its phone to finish. Only the hash is stored.
   *
   * @param createdBy user id of the manager, or {@link #SELF}
   */
  public PairingCodeResponse createCode(String shopId, String employeeId, String createdBy) {
    Employee employee = requireEmployee(shopId, employeeId);
    Instant now = now();
    if (isExited(employee, today(now))) {
      throw new BaseException(ErrorCode.HR_PAIRING_EMPLOYEE_EXITED,
          ErrorCode.HR_PAIRING_EMPLOYEE_EXITED.getMessage());
    }
    List<PairingCode> unused = codeRepository.findByShopIdAndEmployeeIdAndStatus(shopId,
        employeeId, PairingCodeStatus.UNUSED);
    if (!unused.isEmpty()) {
      unused.forEach(c -> c.setStatus(PairingCodeStatus.CANCELLED));
      codeRepository.saveAll(unused);
    }
    PairingCode stored = null;
    String code = null;
    for (int i = 0; i < CODE_GENERATION_TRIES && stored == null; i++) {
      code = PairingCodes.generate(random);
      PairingCode doc = new PairingCode();
      doc.setShopId(shopId);
      doc.setEmployeeId(employeeId);
      doc.setCodeHash(PairingCodes.hash(code));
      doc.setStatus(PairingCodeStatus.UNUSED);
      doc.setCreatedBy(createdBy);
      doc.setCreatedAt(now);
      doc.setExpiresAt(now.plus(CODE_VALIDITY));
      doc.setAttempts(0);
      try {
        stored = codeRepository.insert(doc);
      } catch (DuplicateKeyException e) {
        // a live code with the same 40 bits: draw again
        log.debug("[hr-pairing] pairing code collision for shop {}, retrying", shopId);
      }
    }
    if (stored == null) {
      throw new BaseException(ErrorCode.INTERNAL_SERVER_ERROR,
          "Could not create a pairing code. Try again");
    }
    Map<String, Object> after = new LinkedHashMap<>();
    after.put("expiresAt", stored.getExpiresAt().toString());
    after.put("createdBy", createdBy);
    auditService.record(new AuditEntry(shopId, createdBy, HrAuditAction.PAIRING_CODE_CREATED,
        "PairingCode", stored.getId(), employeeId, null, null, null, after, null));
    countEvent(shopId, AppPairingEvent.CREATED, AppPairingFailure.NONE);
    return new PairingCodeResponse(code, PairingCodes.qrPayload(code), stored.getExpiresAt());
  }

  // ---- claim ---------------------------------------------------------------------------------

  /**
   * Step one of the handshake (R2.6, R2.14): the phone sends the code it scanned or typed and, when
   * the code is good, gets the names to confirm, the consent text and a claim token.
   *
   * <p>Refusals, in order: expired, already used (or claimed by another phone), cancelled, wrong,
   * biometric app off for the shop, employee exited. Each attempt that reaches a code document is
   * counted on it; the 11th invalidates the code (R9.10). A code that matches no document is
   * "wrong" and counts against the installation only (the caller's rate limiter).
   */
  public ClaimPairingResponse claim(String code, String installationId, DeviceOs os,
      String osVersion, String phoneModel, String appVersion) {
    String normalised = PairingCodes.normalise(code);
    if (!PairingCodes.isValid(normalised)) {
      throw failed(null, ErrorCode.HR_PAIRING_CODE_WRONG, AppPairingFailure.CODE_WRONG);
    }
    if (!StringUtils.hasText(installationId)) {
      throw new ValidationException("installationId is required");
    }
    PairingCode doc = codeRepository.findByCodeHash(PairingCodes.hash(normalised)).orElse(null);
    if (doc == null) {
      throw failed(null, ErrorCode.HR_PAIRING_CODE_WRONG, AppPairingFailure.CODE_WRONG);
    }
    Instant now = now();
    countAttempt(doc);
    refuseIfNotClaimable(doc, installationId, now);
    Employee employee = requireEmployee(doc.getShopId(), doc.getEmployeeId());
    refuseIfShopOrEmployeeBlocks(doc, employee, now);

    String token = newClaimToken();
    doc.setStatus(PairingCodeStatus.CLAIMED);
    doc.setClaimTokenHash(sha256Hex(token));
    doc.setClaimedByInstallationId(installationId);
    doc.setClaimedAt(now);
    doc.setClaimedOs(os);
    doc.setClaimedOsVersion(osVersion);
    doc.setClaimedPhoneModel(phoneModel);
    doc.setClaimedAppVersion(appVersion);
    codeRepository.save(doc);
    return new ClaimPairingResponse(token, shopName(doc.getShopId()), employee.getName(),
        consentVersions.latest());
  }

  /**
   * One more attempt on this document; the 11th on a live ({@code UNUSED} or {@code CLAIMED}) code
   * invalidates it and refuses (R9.10). A code that is already {@code USED}, {@code CANCELLED} or
   * {@code INVALIDATED} is still counted for the record but never answers "too many attempts": the
   * caller's status check tells the worker what actually happened to the code.
   */
  private void countAttempt(PairingCode doc) {
    int attempts = (doc.getAttempts() != null ? doc.getAttempts() : 0) + 1;
    doc.setAttempts(attempts);
    if (attempts > MAX_CODE_ATTEMPTS && isLive(doc.getStatus())) {
      doc.setStatus(PairingCodeStatus.INVALIDATED);
      codeRepository.save(doc);
      throw failed(doc.getShopId(), ErrorCode.HR_TOO_MANY_ATTEMPTS, AppPairingFailure.LOCKED);
    }
    codeRepository.save(doc);
  }

  /** A code that could still pair: not yet used, cancelled or invalidated. */
  private static boolean isLive(PairingCodeStatus status) {
    return status == PairingCodeStatus.UNUSED || status == PairingCodeStatus.CLAIMED;
  }

  /** Expired → used → cancelled, in that order (R2.14). A phone may re-claim its own code. */
  private void refuseIfNotClaimable(PairingCode doc, String installationId, Instant now) {
    if (isExpired(doc, now)) {
      throw failed(doc.getShopId(), ErrorCode.HR_PAIRING_CODE_EXPIRED,
          AppPairingFailure.CODE_EXPIRED);
    }
    switch (doc.getStatus()) {
      case UNUSED -> { }
      case CLAIMED -> {
        if (!installationId.equals(doc.getClaimedByInstallationId())) {
          // another phone got there first: to this one the code is spent
          throw failed(doc.getShopId(), ErrorCode.HR_PAIRING_CODE_USED,
              AppPairingFailure.CODE_USED);
        }
      }
      case USED -> throw failed(doc.getShopId(), ErrorCode.HR_PAIRING_CODE_USED,
          AppPairingFailure.CODE_USED);
      case CANCELLED, INVALIDATED -> throw failed(doc.getShopId(),
          ErrorCode.HR_PAIRING_CODE_CANCELLED, AppPairingFailure.CODE_CANCELLED);
    }
  }

  /** Biometric app off for the shop, then employee exited (R2.14). */
  private void refuseIfShopOrEmployeeBlocks(PairingCode doc, Employee employee, Instant now) {
    if (!biometricAppOn(doc.getShopId())) {
      throw failed(doc.getShopId(), ErrorCode.HR_APP_OFF, AppPairingFailure.APP_OFF);
    }
    if (isExited(employee, today(now))) {
      throw failed(doc.getShopId(), ErrorCode.HR_PAIRING_EMPLOYEE_EXITED,
          AppPairingFailure.EMPLOYEE_EXITED);
    }
  }

  // ---- complete / abandon --------------------------------------------------------------------

  /**
   * Step two, "I agree" (R2.9–R2.11, R5.1): registers the phone's two public keys and makes the
   * pairing {@code ACTIVE}. The employee's previous active phone becomes {@code UNPAIRED} with
   * reason {@code REPLACED}; the installation's previous active pairing (to another employee)
   * with reason {@code PAIRED_TO_ANOTHER_EMPLOYEE}. The code is marked used and deleted.
   *
   * @param punchPublicKey Base64 DER SubjectPublicKeyInfo of the biometric-gated P-256 key
   * @param devicePublicKey Base64 DER SubjectPublicKeyInfo of the no-prompt P-256 key
   * @param consentVersion the version shown by {@link #claim}; recorded with the agreement time
   */
  public PairingBootstrapResponse complete(String claimToken, String installationId,
      String punchPublicKey, String devicePublicKey, Integer consentVersion) {
    return complete(claimToken, installationId, punchPublicKey, devicePublicKey, consentVersion,
        null);
  }

  /**
   * {@link #complete(String, String, String, String, Integer)} with the phone's Android key
   * attestation chain for the punch key (task 16.4). When {@code attestationChain} is non-empty it
   * is verified by {@link KeyAttestation} against the claim token as challenge and the punch key;
   * the verdict is stored on the pairing ({@code attested}, {@code attestationSecurityLevel},
   * {@code attestationFailureReason}) and counted as {@code event=attestation}. A failed verdict
   * does not refuse the pairing in this version. Null or empty: nothing is verified or recorded,
   * exactly as before.
   *
   * @param attestationChain Base64 DER X.509 certificates, leaf first; null or empty when the phone
   *     sent none
   */
  public PairingBootstrapResponse complete(String claimToken, String installationId,
      String punchPublicKey, String devicePublicKey, Integer consentVersion,
      List<String> attestationChain) {
    PairingCode doc = requireClaimed(claimToken, installationId);
    Instant now = now();
    try {
      AppSignatures.parseP256PublicKey(punchPublicKey);
    } catch (IllegalArgumentException | NullPointerException e) {
      throw new ValidationException(REASON_BAD_PUNCH_KEY);
    }
    try {
      AppSignatures.parseP256PublicKey(devicePublicKey);
    } catch (IllegalArgumentException | NullPointerException e) {
      throw new ValidationException(REASON_BAD_DEVICE_KEY);
    }
    if (consentVersion == null || consentVersions.get(consentVersion).isEmpty()) {
      throw new ValidationException(REASON_UNKNOWN_CONSENT);
    }
    KeyAttestation.Result attestation = attestationChain != null && !attestationChain.isEmpty()
        ? keyAttestation.verify(attestationChain, KeyAttestation.challengeOf(claimToken),
            punchPublicKey)
        : null;
    String shopId = doc.getShopId();
    String employeeId = doc.getEmployeeId();
    Employee employee = requireEmployee(shopId, employeeId);
    String actor = doc.getCreatedBy();

    // displace by conditional updates, so an unpair racing with this one lands exactly once
    DevicePairing replaced = markUnpaired(
        Query.query(Criteria.where("shopId").is(shopId).and("employeeId").is(employeeId)
            .and("status").is(PairingStatus.ACTIVE)),
        UnpairReason.REPLACED, actor, now);
    DevicePairing moved = markUnpaired(
        Query.query(Criteria.where("installationId").is(installationId)
            .and("status").is(PairingStatus.ACTIVE)),
        UnpairReason.PAIRED_TO_ANOTHER_EMPLOYEE, actor, now);

    DevicePairing pairing = new DevicePairing();
    pairing.setShopId(shopId);
    pairing.setEmployeeId(employeeId);
    pairing.setStatus(PairingStatus.ACTIVE);
    pairing.setPunchPublicKey(punchPublicKey.trim());
    pairing.setDevicePublicKey(devicePublicKey.trim());
    pairing.setInstallationId(installationId);
    pairing.setOs(doc.getClaimedOs());
    pairing.setOsVersion(doc.getClaimedOsVersion());
    pairing.setPhoneModel(doc.getClaimedPhoneModel());
    pairing.setAppVersion(doc.getClaimedAppVersion());
    pairing.setPairedAt(now);
    pairing.setConsentAt(now);
    pairing.setConsentVersion(consentVersion);
    pairing.setConsents(new ArrayList<>(List.of(new DevicePairing.Consent(consentVersion, now))));
    pairing.setPairedBy(actor);
    pairing.setCreatedAt(now);
    if (attestation != null) {
      pairing.setAttested(attestation.ok());
      pairing.setAttestationSecurityLevel(attestation.securityLevel());
      pairing.setAttestationFailureReason(attestation.ok() ? null : attestation.reason());
    }
    DevicePairing saved;
    try {
      saved = pairingRepository.insert(pairing);
    } catch (DuplicateKeyException e) {
      // another pairing for this employee or installation landed between the displacement and
      // this insert; the phone can try the code again
      throw new BaseException(ErrorCode.HR_CONCURRENT_UPDATE,
          ErrorCode.HR_CONCURRENT_UPDATE.getMessage());
    }

    doc.setStatus(PairingCodeStatus.USED);
    doc.setPairingId(saved.getId());
    codeRepository.save(doc);
    codeRepository.delete(doc);

    if (moved != null) {
      // the first employee's history shows the phone left (R2.11, R2.17)
      auditUnpaired(moved, UnpairReason.PAIRED_TO_ANOTHER_EMPLOYEE, actor);
      countEvent(moved.getShopId(), AppPairingEvent.UNPAIRED,
          UnpairReason.PAIRED_TO_ANOTHER_EMPLOYEE.name());
    }
    Map<String, Object> after = new LinkedHashMap<>();
    after.put("phoneModel", saved.getPhoneModel());
    after.put("os", String.valueOf(saved.getOs()));
    after.put("pairedBy", actor);
    after.put("consentVersion", consentVersion);
    if (attestation != null) {
      after.put("attested", attestation.ok());
      if (!attestation.ok()) {
        after.put("attestationFailureReason", attestation.reason());
      }
      countEvent(shopId, AppPairingEvent.ATTESTATION, attestation.reason());
      if (!attestation.ok()) {
        log.info("[hr-pairing] key attestation failed for pairing {} ({}): {}", saved.getId(),
            saved.getPhoneModel(), attestation.reason());
      }
    }
    if (replaced != null) {
      Map<String, Object> before = new LinkedHashMap<>();
      before.put("phoneModel", replaced.getPhoneModel());
      before.put("os", String.valueOf(replaced.getOs()));
      auditService.record(new AuditEntry(shopId, actor, HrAuditAction.DEVICE_REPLACED,
          "DevicePairing", saved.getId(), employeeId, null, null, before, after,
          UnpairReason.REPLACED.name()));
      countEvent(shopId, AppPairingEvent.REPLACED, AppPairingFailure.NONE);
    } else {
      auditService.record(new AuditEntry(shopId, actor, HrAuditAction.DEVICE_PAIRED,
          "DevicePairing", saved.getId(), employeeId, null, null, null, after, null));
      countEvent(shopId, AppPairingEvent.COMPLETED, AppPairingFailure.NONE);
    }
    return new PairingBootstrapResponse(saved.getId(), shopId, employeeId, employee.getName(),
        shopName(shopId), locationSettings(shopId), consentVersion);
  }

  /**
   * "No, this isn't me" or "Don't agree" (R2.6, R2.8): the code can never pair. Unknown or
   * already spent tokens are ignored, so a retry after a lost answer is harmless.
   */
  public void abandon(String claimToken) {
    PairingCode doc = findByClaimToken(claimToken);
    if (doc == null) {
      return;
    }
    if (doc.getStatus() == PairingCodeStatus.CLAIMED || doc.getStatus() == PairingCodeStatus.UNUSED) {
      doc.setStatus(PairingCodeStatus.INVALIDATED);
      codeRepository.save(doc);
      countEvent(doc.getShopId(), AppPairingEvent.FAILED, AppPairingFailure.ABANDONED);
    }
  }

  /** The claimed code behind the token, or the matching refusal; counts an attempt on it. */
  private PairingCode requireClaimed(String claimToken, String installationId) {
    PairingCode doc = findByClaimToken(claimToken);
    if (doc == null) {
      // used codes are deleted and old ones expire by TTL, so a token that matches nothing is
      // most likely out of its 15 minutes
      throw failed(null, ErrorCode.HR_PAIRING_CODE_EXPIRED, AppPairingFailure.CODE_EXPIRED);
    }
    Instant now = now();
    countAttempt(doc);
    if (isExpired(doc, now)) {
      throw failed(doc.getShopId(), ErrorCode.HR_PAIRING_CODE_EXPIRED,
          AppPairingFailure.CODE_EXPIRED);
    }
    switch (doc.getStatus()) {
      case CLAIMED -> { }
      case USED -> throw failed(doc.getShopId(), ErrorCode.HR_PAIRING_CODE_USED,
          AppPairingFailure.CODE_USED);
      case CANCELLED, INVALIDATED -> throw failed(doc.getShopId(),
          ErrorCode.HR_PAIRING_CODE_CANCELLED, AppPairingFailure.CODE_CANCELLED);
      case UNUSED -> throw failed(doc.getShopId(), ErrorCode.HR_PAIRING_CODE_WRONG,
          AppPairingFailure.CODE_WRONG);
    }
    if (installationId == null || !installationId.equals(doc.getClaimedByInstallationId())) {
      throw failed(doc.getShopId(), ErrorCode.HR_PAIRING_CODE_WRONG, AppPairingFailure.CODE_WRONG);
    }
    return doc;
  }

  private PairingCode findByClaimToken(String claimToken) {
    if (!StringUtils.hasText(claimToken)) {
      return null;
    }
    return codeRepository.findByClaimTokenHash(sha256Hex(claimToken.trim())).orElse(null);
  }

  // ---- unpair --------------------------------------------------------------------------------

  /**
   * Ends a pairing (R2.12, R3.1, R3.7, R3.9). One conditional update {@code ACTIVE → UNPAIRED};
   * when the pairing is already unpaired or unknown nothing changes and empty is returned, so
   * every route is idempotent.
   *
   * @param by user id, {@link #SELF} or {@link #SYSTEM}
   * @return the pairing as it is now stored, when this call unpaired it
   */
  public Optional<DevicePairing> unpair(String pairingId, UnpairReason reason, String by) {
    if (!StringUtils.hasText(pairingId)) {
      return Optional.empty();
    }
    DevicePairing unpaired = markUnpaired(
        Query.query(Criteria.where("_id").is(pairingId).and("status").is(PairingStatus.ACTIVE)),
        reason, by, now());
    if (unpaired == null) {
      return Optional.empty();
    }
    auditUnpaired(unpaired, reason, by);
    countEvent(unpaired.getShopId(), AppPairingEvent.UNPAIRED, reason.name());
    return Optional.of(unpaired);
  }

  /** The employee's active pairing, unpaired; empty when there was none (R2.12, R3.1). */
  public Optional<DevicePairing> unpairActive(String shopId, String employeeId, UnpairReason reason,
      String by) {
    return activePairing(shopId, employeeId)
        .flatMap(p -> unpair(p.getId(), reason, by));
  }

  // ---- consent -------------------------------------------------------------------------------

  /**
   * The worker tapped "I agree" on a consent version after pairing (R9.13). One conditional update
   * on the {@code ACTIVE} pairing: {@code consentVersion} rises to {@code version} (never lower,
   * {@code $max}), {@code consentAt} is now, and the agreement is appended to {@code consents[]}.
   * Writes one {@code DEVICE_CONSENT_UPDATED} audit row with the old and new version, actor {@link
   * #SELF}.
   *
   * @param version a published version ({@link ConsentVersions#get}); unknown → 400
   * @throws ValidationException when the version was never published
   * @throws BaseException {@code HR_APP_NOT_PAIRED} when the pairing is not active any more
   */
  public ConsentResponse recordConsent(String pairingId, Integer version) {
    if (version == null || consentVersions.get(version).isEmpty()) {
      throw new ValidationException(REASON_UNKNOWN_CONSENT);
    }
    Instant now = now();
    Update update = new Update()
        .max("consentVersion", version)
        .set("consentAt", now)
        .push("consents", new DevicePairing.Consent(version, now));
    DevicePairing before = StringUtils.hasText(pairingId)
        ? mongoTemplate.findAndModify(
            Query.query(Criteria.where("_id").is(pairingId).and("status").is(PairingStatus.ACTIVE)),
            update, FindAndModifyOptions.options().returnNew(false), DevicePairing.class)
        : null;
    if (before == null) {
      throw new BaseException(ErrorCode.HR_APP_NOT_PAIRED, ErrorCode.HR_APP_NOT_PAIRED.getMessage());
    }
    int previous = before.getConsentVersion() != null ? before.getConsentVersion() : 0;
    int agreed = Math.max(previous, version);
    Map<String, Object> was = new LinkedHashMap<>();
    was.put("consentVersion", before.getConsentVersion());
    Map<String, Object> is = new LinkedHashMap<>();
    is.put("consentVersion", agreed);
    is.put("agreedVersion", version);
    is.put("phoneModel", before.getPhoneModel());
    auditService.record(new AuditEntry(before.getShopId(), SELF,
        HrAuditAction.DEVICE_CONSENT_UPDATED, "DevicePairing", before.getId(),
        before.getEmployeeId(), null, null, was, is, null));
    return new ConsentResponse(agreed, now);
  }

  // ---- exit ----------------------------------------------------------------------------------

  /**
   * The employee is {@code EXITED} from {@code exitDate} (R3.4, R3.5). The employee's active
   * pairing gets {@code scheduledUnpairAt = exitDate 00:00 Asia/Kolkata}; when that moment has
   * already arrived (exit today or earlier) the pairing is unpaired at once as {@code
   * EMPLOYEE_EXITED} by {@link #SYSTEM}. The schedule is written first, so if the immediate unpair
   * fails {@link PairingExitJob} finishes it on its next tick. Calling again with a new date moves
   * the schedule; no active pairing means nothing is written.
   *
   * @return the pairing when this call unpaired it now; empty when it was only scheduled or there
   *     was no active pairing
   */
  public Optional<DevicePairing> scheduleExitUnpair(String shopId, String employeeId,
      LocalDate exitDate) {
    if (exitDate == null) {
      clearExitUnpair(shopId, employeeId);
      return Optional.empty();
    }
    Instant at = exitDate.atStartOfDay(HrDateUtils.ZONE).toInstant();
    mongoTemplate.updateFirst(activeOf(shopId, employeeId),
        new Update().set("scheduledUnpairAt", at), DevicePairing.class);
    if (at.isAfter(now())) {
      return Optional.empty();
    }
    return unpairActive(shopId, employeeId, UnpairReason.EMPLOYEE_EXITED, SYSTEM);
  }

  /**
   * The employee is no longer leaving (R3.5): a pending exit schedule on the active pairing is
   * removed and the phone stays paired. Harmless when there is none.
   */
  public void clearExitUnpair(String shopId, String employeeId) {
    mongoTemplate.updateFirst(activeOf(shopId, employeeId),
        new Update().unset("scheduledUnpairAt"), DevicePairing.class);
  }

  private static Query activeOf(String shopId, String employeeId) {
    return Query.query(Criteria.where("shopId").is(shopId).and("employeeId").is(employeeId)
        .and("status").is(PairingStatus.ACTIVE));
  }

  private DevicePairing markUnpaired(Query active, UnpairReason reason, String by, Instant now) {
    Update update = new Update()
        .set("status", PairingStatus.UNPAIRED)
        .set("unpairedAt", now)
        .set("unpairedBy", by)
        .set("unpairReason", reason)
        .set("purgeAt", now.plus(UNPAIRED_RETENTION))
        .unset("scheduledUnpairAt");
    return mongoTemplate.findAndModify(active, update,
        FindAndModifyOptions.options().returnNew(true), DevicePairing.class);
  }

  private void auditUnpaired(DevicePairing p, UnpairReason reason, String by) {
    Map<String, Object> before = new LinkedHashMap<>();
    before.put("phoneModel", p.getPhoneModel());
    before.put("os", String.valueOf(p.getOs()));
    before.put("pairedAt", p.getPairedAt() != null ? p.getPairedAt().toString() : null);
    auditService.record(new AuditEntry(p.getShopId(), by, HrAuditAction.DEVICE_UNPAIRED,
        "DevicePairing", p.getId(), p.getEmployeeId(), null, null, before, null, reason.name()));
  }

  // ---- reads ---------------------------------------------------------------------------------

  /** The employee's active pairing, if any (D3: at most one). */
  public Optional<DevicePairing> activePairing(String shopId, String employeeId) {
    return pairingRepository.findByShopIdAndEmployeeIdAndStatus(shopId, employeeId,
        PairingStatus.ACTIVE);
  }

  /** Active pairings of several employees, by employee id; one read (R1.9, R8.12). */
  public Map<String, DevicePairing> activePairingsByEmployee(String shopId,
      Collection<String> employeeIds) {
    Map<String, DevicePairing> out = new LinkedHashMap<>();
    if (employeeIds == null || employeeIds.isEmpty()) {
      return out;
    }
    for (DevicePairing p : pairingRepository.findByShopIdAndEmployeeIdInAndStatus(shopId,
        employeeIds, PairingStatus.ACTIVE)) {
      out.putIfAbsent(p.getEmployeeId(), p);
    }
    return out;
  }

  /** Ids of every employee of the shop with an active pairing; one read (the {@code ?paired=} filter, R8.12). */
  public Set<String> activePairedEmployeeIds(String shopId) {
    Set<String> ids = new LinkedHashSet<>();
    for (DevicePairing p : pairingRepository.findByShopIdAndStatus(shopId, PairingStatus.ACTIVE)) {
      ids.add(p.getEmployeeId());
    }
    return ids;
  }

  /** What the Attendance app section of the employee's page shows (R2.2). */
  public PairingStateResponse pairingState(String shopId, String employeeId) {
    requireEmployee(shopId, employeeId);
    PairingStateResponse r = new PairingStateResponse();
    DevicePairing p = activePairing(shopId, employeeId).orElse(null);
    if (p == null) {
      r.setState(PairingStateResponse.State.NOT_PAIRED);
    } else {
      r.setState(PairingStateResponse.State.PAIRED);
      r.setPhoneModel(p.getPhoneModel());
      r.setOs(p.getOs());
      r.setPairedAt(p.getPairedAt());
      r.setLastPunchAt(p.getLastPunchAt());
      r.setPairedBy(p.getPairedBy());
      r.setConsentVersion(p.getConsentVersion());
      r.setAttested(p.getAttested());
    }
    Instant now = now();
    List<PairingCode> pending = new ArrayList<>();
    pending.addAll(codeRepository.findByShopIdAndEmployeeIdAndStatus(shopId, employeeId,
        PairingCodeStatus.UNUSED));
    pending.addAll(codeRepository.findByShopIdAndEmployeeIdAndStatus(shopId, employeeId,
        PairingCodeStatus.CLAIMED));
    pending.stream()
        .filter(c -> !isExpired(c, now))
        .max(Comparator.comparing(PairingCode::getExpiresAt))
        .ifPresent(c -> r.setPendingCode(new PairingStateResponse.PendingCode(c.getExpiresAt())));
    return r;
  }

  /** The shop's Location check settings as the phone needs them (DD6). */
  AppLocationSettingsResponse locationSettings(String shopId) {
    return locationSettings(capture(shopId));
  }

  /** {@link #locationSettings(String)} from capture settings already read. */
  static AppLocationSettingsResponse locationSettings(HrSettings.AttendanceCapture c) {
    boolean on = Boolean.TRUE.equals(c.getLocationCheck());
    return new AppLocationSettingsResponse(on, on ? c.getShopLat() : null,
        on ? c.getShopLng() : null, c.getRadiusMetres(), c.getOutsideAction());
  }

  /**
   * An accepted punch through the pairing (R2.2 "last punch"). {@code $max}, so a late offline
   * punch for an earlier day never moves the time backwards. One update, no read.
   */
  void recordLastPunch(String pairingId, Instant at) {
    mongoTemplate.updateFirst(Query.query(Criteria.where("_id").is(pairingId)),
        new Update().max("lastPunchAt", at), DevicePairing.class);
  }

  // ---- helpers -------------------------------------------------------------------------------

  private Employee requireEmployee(String shopId, String employeeId) {
    return employeeRepository.findByShopIdAndId(shopId, employeeId)
        .orElseThrow(() -> new ResourceNotFoundException("Employee", "id", employeeId));
  }

  /** EXITED and the exit date has arrived (the phone is unpaired at 00:00 of that day, R3.4). */
  static boolean isExited(Employee employee, LocalDate today) {
    if (employee.getStatus() != EmployeeStatus.EXITED) {
      return false;
    }
    LocalDate exit = HrDateUtils.parseDate(employee.getExitDate());
    return exit == null || !today.isBefore(exit);
  }

  static boolean isExpired(PairingCode code, Instant now) {
    return code.getExpiresAt() == null || !now.isBefore(code.getExpiresAt());
  }

  private boolean biometricAppOn(String shopId) {
    return Boolean.TRUE.equals(capture(shopId).getBiometricApp());
  }

  private HrSettings.AttendanceCapture capture(String shopId) {
    HrSettings settings = settingsService.effectiveSettings(shopId);
    HrSettings.AttendanceCapture c = settings != null ? settings.getAttendanceCapture() : null;
    return c != null ? c : HrSettingsService.captureWithDefaults(null);
  }

  /** The shop's display name, or "your shop" when the shop is unknown or has no name. */
  String shopName(String shopId) {
    String name = shopNamePort.getShopName(shopId);
    return StringUtils.hasText(name) ? name : "your shop";
  }

  private String newClaimToken() {
    byte[] bytes = new byte[CLAIM_TOKEN_BYTES];
    random.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  static String sha256Hex(String value) {
    try {
      return HEX.formatHex(MessageDigest.getInstance("SHA-256")
          .digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is required", e);
    }
  }

  private Instant now() {
    return Instant.now(clock);
  }

  private static LocalDate today(Instant now) {
    return now.atZone(HrDateUtils.ZONE).toLocalDate();
  }

  /** A pairing refusal, counted as {@code failed{reason}} (R10.3); shop may be unknown. */
  private BaseException failed(String shopId, ErrorCode code, String reason) {
    countEvent(shopId, AppPairingEvent.FAILED, reason);
    return new BaseException(code, code.getMessage());
  }

  private void countEvent(String shopId, String event, String reason) {
    metrics.increment(HrMetricsConstants.APP_PAIRINGS_TOTAL, 1,
        "module", HrMetricsConstants.MODULE,
        HrMetricsConstants.TAG_SHOP, shopId != null ? shopId : "unknown",
        HrMetricsConstants.TAG_EVENT, event,
        HrMetricsConstants.TAG_REASON, reason);
  }
}
