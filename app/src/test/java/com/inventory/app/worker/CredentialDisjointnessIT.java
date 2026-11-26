// Feature: worker-app, Property 21: The two credentials never travel together (server half)
package com.inventory.app.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.inventory.common.constants.ErrorCode;
import com.inventory.hr.domain.model.AppNonce;
import com.inventory.hr.domain.model.DevicePairing;
import com.inventory.hr.domain.model.Employee;
import com.inventory.hr.domain.model.HrAuditEvent;
import com.inventory.hr.domain.model.Punch;
import com.inventory.hr.domain.model.WorkerJoinRequest;
import com.inventory.hr.domain.model.enums.EmployeeStatus;
import com.inventory.hr.service.AttendanceAppAuthInterceptor;
import com.inventory.hr.utils.constants.HrMetricsConstants;
import com.inventory.hr.utils.constants.HrMetricsConstants.AppPunchResult;
import com.inventory.user.domain.model.UserAccount;
import com.inventory.user.domain.model.UserRole;
import com.inventory.user.domain.model.UserToken;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.lifecycle.AddLifecycleHook;
import net.jqwik.api.lifecycle.BeforeTry;
import net.jqwik.api.lifecycle.LifecycleContext;
import net.jqwik.api.lifecycle.PropagationMode;
import net.jqwik.api.lifecycle.SkipExecutionHook;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestContextManager;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Worker-app design Property 21, the server half (R7.6, R9.1): a login and a device signature never
 * travel together, and a request that carries both is refused before any handler runs and before
 * either credential is looked at, so nothing is read, written or counted on its behalf.
 *
 * <p>Two properties over the real application ({@link WorkerAppItSupport}: every interceptor
 * {@code CorsConfig} registers, the real exception handler, a real MongoDB), with real tokens and
 * real device-key envelopes made through the public API:
 *
 * <ul>
 *   <li><b>Device side.</b> For any signed device path ({@code /attendance-app/me}, {@code
 *       /punches}, {@code /consent}, {@code /pairings/me/*}), any Bearer token (a signed-in
 *       worker, a signed-in staff member, garbage, an expired session), any envelope beside it (a
 *       valid one, one signed by a stranger's key, none at all) and either kind of pairing (a
 *       WORKER account's phone, or a staff member's, R5.6): the answer is 400 {@code
 *       HR_TWO_CREDENTIALS} with the design text, exactly one {@code signature_failed} is counted
 *       for the pairing's shop, and nothing changed — no punch, no nonce burnt, no audit row, the
 *       pairing and the employee byte-identical.
 *   <li><b>Worker side.</b> For any {@code /hr/worker/**} endpoint, any token (the four above, or
 *       none) and any subset of the four envelope headers: when the subset holds {@code
 *       X-Pairing-Id} or {@code X-Request-Signature} the answer is 400 {@code HR_TWO_CREDENTIALS}
 *       whatever the token says, and nothing changed; when it holds only the timestamp or the
 *       nonce, those two mean nothing on their own and the request is handled as if they were
 *       absent — not authenticated without a usable token, the handler's own answer with one.
 * </ul>
 *
 * <p>jqwik drives the properties; the Spring test context is prepared for each try through the
 * {@link TestContextManager} the JUnit extension itself uses (one context, cached), because the
 * module has no jqwik–Spring bridge. Skipped, like the sibling {@code *IT}s, unless {@value
 * WorkerAppItSupport#MONGO_PROPERTY} names a replica-set MongoDB.
 *
 * <p><b>Validates: Requirements 7.6, 9.1</b>
 */
@AddLifecycleHook(value = CredentialDisjointnessIT.MongoGate.class,
    propagateTo = PropagationMode.ALL_DESCENDANTS)
class CredentialDisjointnessIT extends WorkerAppItSupport {

  /** The same gate as {@code @EnabledIfSystemProperty} on the siblings, for jqwik. */
  static class MongoGate implements SkipExecutionHook {
    @Override
    public SkipResult shouldBeSkipped(LifecycleContext context) {
      String uri = System.getProperty(MONGO_PROPERTY);
      return uri == null || uri.isBlank()
          ? SkipResult.skip("set -D" + MONGO_PROPERTY + " to a replica-set MongoDB URI")
          : SkipResult.doNotSkip();
    }
  }

  @Autowired private MeterRegistry meterRegistry;

  private static TestContextManager testContextManager;
  private static Fixture fx;

  /** Injects the Spring beans into this instance and stages the shared fixture once. */
  @BeforeTry
  void prepare() throws Exception {
    if (testContextManager == null) {
      testContextManager = new TestContextManager(CredentialDisjointnessIT.class);
    }
    testContextManager.prepareTestInstance(this);
    if (fx == null) {
      fx = stage();
    }
  }

  // ---- the inputs ------------------------------------------------------------------------------

  /** A Bearer token as the server may see it. */
  enum Token {
    WORKER, STAFF, GARBAGE, EXPIRED, NONE;

    String value() {
      return switch (this) {
        case WORKER -> fx.worker().token();
        case STAFF -> fx.shop().owner().token();
        case GARBAGE -> "not-a-real-token-" + UUID.randomUUID();
        case EXPIRED -> fx.expiredToken();
        case NONE -> null;
      };
    }

    boolean usable() {
      return this == WORKER || this == STAFF;
    }
  }

  /** Which phone the device call claims to come from; both paired with a login (D11). */
  enum PairingKind {
    /** The worker's phone, paired with the Bearer token (R7.2). */
    LINKED_WORKER,
    /** A staff member's phone: a CASHIER with a linked employee uses the app as a worker (R5.6). */
    LINKED_STAFF;

    Paired paired() {
      return this == LINKED_WORKER ? fx.workerPhone() : fx.staffPhone();
    }
  }

  /** The envelope beside the Bearer token on a device call. */
  enum Envelope { VALID, STRANGER_KEY, NONE }

  /** The signed device paths of the design's Property 21. */
  enum DevicePath {
    ME, PUNCH, HISTORY, CONSENT, UNPAIR;

    /** The request with the envelope asked for, over the given pairing. */
    MockHttpServletRequestBuilder request(Paired p, Envelope envelope) throws Exception {
      Phone signer = switch (envelope) {
        case VALID -> p.phone();
        case STRANGER_KEY -> fx.stranger();
        case NONE -> null;
      };
      String body = switch (this) {
        case PUNCH -> punchBody(p.pairingId(), p.shopId(), p.employeeId(), p.phone());
        case CONSENT -> "{\"version\":" + p.consentVersion() + "}";
        default -> null;
      };
      MockHttpServletRequestBuilder r = switch (this) {
        case ME -> get(DEVICE + "/me");
        case PUNCH -> post(DEVICE + "/punches");
        case HISTORY -> get(DEVICE + "/punches");
        case CONSENT -> post(DEVICE + "/consent");
        case UNPAIR -> post(DEVICE + "/pairings/me/unpair");
      };
      if (signer != null) {
        return signed(p.pairingId(), signer, r, null, body);
      }
      if (body != null) {
        r.contentType(MediaType.APPLICATION_JSON).content(body);
      }
      return r;
    }
  }

  /** The four envelope headers; the design names the first two as the mark of a device call. */
  enum EnvelopeHeader {
    PAIRING_ID(AttendanceAppAuthInterceptor.HEADER_PAIRING_ID),
    SIGNATURE(AttendanceAppAuthInterceptor.HEADER_SIGNATURE),
    TIMESTAMP(AttendanceAppAuthInterceptor.HEADER_TIMESTAMP),
    NONCE(AttendanceAppAuthInterceptor.HEADER_NONCE);

    final String name;

    EnvelopeHeader(String name) {
      this.name = name;
    }

    /** A value of the right shape: the worker's real pairing id, a plausible signature, now. */
    String value() {
      return switch (this) {
        case PAIRING_ID -> fx.workerPhone().pairingId();
        case SIGNATURE -> "MEUCIQDf3b5G0cZ9mJ7y1a6QhV3kq0f3n0pZ4lTq9g2cXvYbGwIgC8l4d2k0mA7oP5uR1xN6tW3zH9sJ0eQ2yB4vL8kFgT0=";
        case TIMESTAMP -> Long.toString(Instant.now().getEpochSecond());
        case NONCE -> UUID.randomUUID().toString();
      };
    }

    /** Whether this header alone makes the request a device call in the server's eyes. */
    boolean marksADeviceCall() {
      return this == PAIRING_ID || this == SIGNATURE;
    }
  }

  /** Every {@code WorkerController} endpoint, with a well-formed body where one is needed. */
  enum WorkerPath {
    ME("GET", "/me", null),
    SHOPS("GET", "/shops?ownerEmail=owner@example.test", null),
    JOIN("POST", "/join-requests", "{\"shopId\":\"%s\"}"),
    WITHDRAW("POST", "/join-requests/5f1a2b3c4d5e6f7a8b9c0d1e/withdraw", null),
    CLAIM("POST", "/pairings/claim", "{\"code\":\"ABCD1234\",\"installationId\":\"%s\","
        + "\"os\":\"ANDROID\",\"osVersion\":\"14\",\"phoneModel\":\"Redmi Note 11\","
        + "\"appVersion\":\"2.0.0\"}"),
    ABANDON("POST", "/pairings/abandon", "{\"claimToken\":\"not-a-claim-token\"}"),
    PUNCHES("GET", "/punches", null),
    PROFILE("GET", "/profile", null),
    EDIT_PROFILE("PUT", "/profile", "{\"name\":\"Sunita Devi\",\"phone\":\"9876500071\"}"),
    LEAVE("POST", "/leave", null),
    NOTIFICATIONS("GET", "/notifications", null),
    READ("POST", "/notifications/5f1a2b3c4d5e6f7a8b9c0d1e/read", null);

    final String method;
    final String path;
    final String body;

    WorkerPath(String method, String path, String body) {
      this.method = method;
      this.path = path;
      this.body = body;
    }

    boolean readOnly() {
      return "GET".equals(method);
    }

    MockHttpServletRequestBuilder request() {
      MockHttpServletRequestBuilder r = switch (method) {
        case "GET" -> get(WORKER + path);
        case "PUT" -> put(WORKER + path);
        default -> post(WORKER + path);
      };
      if (body != null) {
        String filled = body.contains("%s")
            ? body.formatted(this == JOIN ? fx.shop().shopId() : UUID.randomUUID().toString())
            : body;
        r.contentType(MediaType.APPLICATION_JSON).content(filled);
      }
      return r;
    }
  }

  // ---- device side -----------------------------------------------------------------------------

  @Property(tries = 30)
  void aDeviceCallCarryingALoginIsRefusedBeforeAnythingIsReadWrittenOrVerified(
      @ForAll DevicePath path, @ForAll("bearerTokens") Token token, @ForAll Envelope envelope,
      @ForAll PairingKind kind) throws Exception {
    Paired p = kind.paired();
    State before = state(p);
    // the counter is labelled with the pairing's shop when the header names a known pairing,
    // "unknown" when the login arrived without any envelope
    String shopLabel = envelope == Envelope.NONE ? "unknown" : p.shopId();
    double failedBefore = signatureFailed(shopLabel);

    MockHttpServletRequestBuilder request = path.request(p, envelope)
        .header("Authorization", "Bearer " + token.value());
    expectRefusal(request, ErrorCode.HR_TWO_CREDENTIALS);

    assertEquals(failedBefore + 1, signatureFailed(shopLabel),
        path + "/" + token + "/" + envelope + ": exactly one signature_failed for " + shopLabel);
    assertEquals(before, state(p), path + "/" + token + "/" + envelope + "/" + kind
        + ": a refused device call changes nothing");
  }

  /** Something in the {@code Authorization} header: the four kinds, never absent. */
  @Provide
  Arbitrary<Token> bearerTokens() {
    return Arbitraries.of(Token.WORKER, Token.STAFF, Token.GARBAGE, Token.EXPIRED);
  }

  // ---- worker side -----------------------------------------------------------------------------

  @Property(tries = 30)
  void aWorkerCallCarryingAnEnvelopeHeaderIsRefusedBeforeTheTokenIsRead(
      @ForAll WorkerPath path, @ForAll Token token,
      @ForAll("envelopeHeaders") Set<EnvelopeHeader> headers) throws Exception {
    Paired p = fx.workerPhone();
    State before = state(p);
    boolean deviceMarked = headers.stream().anyMatch(EnvelopeHeader::marksADeviceCall);
    String label = path + "/" + token + "/" + headers;

    MockHttpServletRequestBuilder request = path.request();
    if (token != Token.NONE) {
      request.header("Authorization", "Bearer " + token.value());
    }
    for (EnvelopeHeader h : headers) {
      request.header(h.name, h.value());
    }

    if (deviceMarked) {
      // refused whatever the token is — valid, garbage, expired or missing: the token is never read
      expectRefusal(request, ErrorCode.HR_TWO_CREDENTIALS);
      assertEquals(before, state(p), label + ": a refused worker call changes nothing");
      return;
    }
    // the timestamp and the nonce alone are not a device credential: the Bearer chain decides
    if (!token.usable()) {
      mvc.perform(request)
          .andExpect(status().isUnauthorized())
          .andExpect(jsonPath("$.success").value(false));
      assertEquals(before, state(p), label + ": not authenticated changes nothing");
      return;
    }
    if (!path.readOnly()) {
      // a signed-in call that would run the handler and change data: that is the endpoint's own
      // behaviour (WorkerControllerIT), not this property's; nothing to refuse here
      return;
    }
    MvcResult r = mvc.perform(request).andReturn();
    assertFalse(r.getResponse().getStatus() == ErrorCode.HR_TWO_CREDENTIALS.getHttpStatus().value()
            && json(r).contains(ErrorCode.HR_TWO_CREDENTIALS.getMessage()),
        label + ": a worker call without a device credential is not refused as two credentials");
    if (path == WorkerPath.ME && token == Token.WORKER) {
      assertEquals(200, r.getResponse().getStatus(), label + ": the handler ran");
    }
  }

  /** Any subset of the four envelope headers, the empty one included. */
  @Provide
  Arbitrary<Set<EnvelopeHeader>> envelopeHeaders() {
    return Arbitraries.of(EnvelopeHeader.class).set().ofMinSize(0).ofMaxSize(4);
  }

  // ---- what "nothing changed" means ------------------------------------------------------------

  /**
   * Everything a credential mix-up could have touched: the rows a device call or a worker call
   * writes, and the two documents they act on, as raw BSON so a new field is caught too.
   */
  record State(long punches, long nonces, long auditRows, long joinRequests, Document pairing,
      Document employee, Document account) {}

  private State state(Paired p) {
    Criteria shop = Criteria.where("shopId").is(p.shopId());
    Criteria pairing = Criteria.where("pairingId").is(p.pairingId());
    return new State(
        template.count(Query.query(shop), Punch.class),
        template.count(Query.query(pairing), AppNonce.class),
        template.count(Query.query(shop), HrAuditEvent.class),
        template.count(Query.query(shop), WorkerJoinRequest.class),
        raw(DevicePairing.class, p.pairingId()),
        raw(Employee.class, p.employeeId()),
        accountFields(fx.worker().userId()));
  }

  /** The document as stored, every field included, so a changed or added field is caught. */
  private Document raw(Class<?> entity, String id) {
    Document d = template.findOne(Query.query(Criteria.where("_id").is(new ObjectId(id))),
        Document.class, template.getCollectionName(entity));
    assertNotNull(d, entity.getSimpleName() + " " + id);
    return d;
  }

  /** The account fields a worker call could edit; not the token list, which a cleanup may trim. */
  private Document accountFields(String userId) {
    UserAccount a = template.findById(userId, UserAccount.class);
    assertNotNull(a, "users " + userId);
    return new Document("name", a.getName()).append("phone", a.getPhone())
        .append("email", a.getEmail()).append("shopId", a.getShopId())
        .append("role", a.getRole() == null ? null : a.getRole().name())
        .append("active", a.isActive());
  }

  private double signatureFailed(String shopLabel) {
    Counter c = meterRegistry.find(HrMetricsConstants.APP_PUNCHES_TOTAL)
        .tags("module", HrMetricsConstants.MODULE,
            HrMetricsConstants.TAG_SHOP, shopLabel,
            HrMetricsConstants.TAG_RESULT, AppPunchResult.SIGNATURE_FAILED)
        .counter();
    return c == null ? 0 : c.count();
  }

  // ---- the shared fixture ----------------------------------------------------------------------

  /** A phone paired to an employee of the shop, with what the device paths need to be signed. */
  record Paired(Phone phone, String pairingId, String shopId, String employeeId,
      int consentVersion) {}

  /** One shop, a signed-in worker and a cashier each with a paired phone, and the odd tokens. */
  record Fixture(ShopFixture shop, Session worker, Paired workerPhone, Paired staffPhone,
      Phone stranger, String expiredToken) {}

  private Fixture stage() throws Exception {
    ShopFixture shop = newShop("Vikram Shah", "Shah Stores");
    Session worker = activeWorker(shop, "Sunita Devi", "9876500071");
    Employee mine = template.findOne(Query.query(Criteria.where("userId").is(worker.userId())
        .and("status").is(EmployeeStatus.ACTIVE)), Employee.class);
    assertNotNull(mine);
    Phone phone = Phone.create();
    String pairingId = pairWithLogin(worker, createCode(shop, mine.getId()), phone);
    Paired workerPhone = new Paired(phone, pairingId, shop.shopId(), mine.getId(),
        consentVersion(pairingId));

    // a cashier with a linked employee pairs through the same handshake with their own login
    Session cashier = staffMember(shop, "Mohan Lal", UserRole.CASHIER);
    Employee staffRecord = employee(shop, "EMP-STAFF", "Mohan Lal", cashier.userId());
    Phone staffDevice = Phone.create();
    String staffPairing = pairWithLogin(cashier, createCode(shop, staffRecord.getId()),
        staffDevice);
    Paired staffPhone = new Paired(staffDevice, staffPairing, shop.shopId(), staffRecord.getId(),
        consentVersion(staffPairing));

    // a second session of the worker, expired by hand: the server must answer "not authenticated"
    Session expired = login(worker);
    UserAccount account = template.findById(worker.userId(), UserAccount.class);
    assertNotNull(account);
    for (UserToken t : account.getTokens()) {
      if (expired.token().equals(t.getAccessToken())) {
        t.setExpiresAt(Instant.now().minusSeconds(60));
      }
    }
    template.save(account);
    mvc.perform(get(WORKER + "/me").header("Authorization", "Bearer " + expired.token()))
        .andExpect(status().isUnauthorized());

    // the envelope-only calls both phones make are accepted: the login is what will be wrong
    mvc.perform(signedPunch(pairingId, shop.shopId(), mine.getId(), phone))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.outcome").value("STORED"));
    mvc.perform(signedPunch(staffPairing, shop.shopId(), staffRecord.getId(), staffDevice))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.outcome").value("STORED"));

    return new Fixture(shop, worker, workerPhone, staffPhone, Phone.create(), expired.token());
  }

  private int consentVersion(String pairingId) {
    DevicePairing pairing = template.findById(pairingId, DevicePairing.class);
    assertNotNull(pairing);
    return pairing.getConsentVersion() != null ? pairing.getConsentVersion() : 1;
  }
}
