package com.inventory.app.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.inventory.app.AppApplication;
import com.inventory.common.constants.ErrorCode;
import com.inventory.hr.domain.model.Employee;
import com.inventory.hr.domain.model.enums.EmployeeStatus;
import com.inventory.hr.service.AttendanceAppAuthInterceptor;
import com.inventory.hr.utils.AppSignatures;
import com.inventory.hr.utils.AppSignatures.EnvelopeFields;
import com.inventory.hr.utils.AppSignatures.PunchFields;
import com.inventory.hr.utils.PairingCodes;
import com.inventory.user.domain.model.UserAccount;
import com.inventory.user.domain.model.UserRole;
import com.inventory.user.domain.model.UserShopMembership;
import com.inventory.user.service.UserShopMembershipService;
import com.jayway.jsonpath.JsonPath;
import com.mongodb.ConnectionString;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * The real application, booted once for the worker-app {@code *IT}s in this package: {@link
 * AppApplication} with every interceptor {@code CorsConfig} registers ({@code
 * WorkerCredentialInterceptor} → {@code AuthenticationInterceptor} → {@code RbacModuleInterceptor}
 * → {@code PlanExpiryInterceptor}, and {@code AttendanceAppAuthInterceptor} on the device paths),
 * the real {@code GlobalExceptionHandler}, the real services and repositories, against a real
 * MongoDB. Accounts, shops and tokens are made through the public API exactly as the web and the
 * Stockkart Team app make them, so every request below carries a real 7-day Bearer token or a real
 * device-key envelope and nothing is set on the request by hand.
 *
 * <p>The approval of a join request runs in a MongoDB transaction (worker-app design DD12), so
 * this needs a replica set, like {@code MongoTxIT}:
 *
 * <pre>
 *   docker run -d --name wa-mongo -p 127.0.0.1:27018:27017 --tmpfs /data/db mongo:7 --replSet rs0
 *   docker exec wa-mongo mongosh --quiet --eval 'rs.initiate({_id:"rs0",members:[{_id:0,host:"127.0.0.1:27017"}]})'
 *   mvn -pl app -am test -Dtest='Worker*IT' -Dsurefire.failIfNoSpecifiedTests=false \
 *       -Dstockkart.test.mongo.rs.uri='mongodb://127.0.0.1:27018/stockkart_worker?directConnection=true'
 * </pre>
 *
 * <p>The database named in the URI is dropped once per JVM, before the context boots, so a re-run
 * starts clean and the start-up index migrations ({@code HrIndexMigration}, {@code
 * UserTokenIndexMigration}) create the real indexes, the partial unique "one open request per
 * account" index included. Every test makes its own owner, shop and workers with unique e-mails,
 * so tests do not see each other's rows.
 *
 * <p>Configuration: the Mongo URI comes from the system property; the OCR, messaging and upload
 * placeholders that have no default in {@code application.properties} (AGENTS C3) are given dummy
 * values; the shop read cache is off so a test that expires a shop's trial by writing the shop
 * document is seen by the very next request; the two exit jobs' scheduled ticks are off because
 * the ITs call {@code WorkerExitJob} and {@code PairingExitJob} directly with a chosen time.
 * Everything else is the application's own default.
 */
@EnabledIfSystemProperty(named = WorkerAppItSupport.MONGO_PROPERTY, matches = ".+")
@SpringBootTest(classes = AppApplication.class, properties = {
    "spring.data.mongodb.uri=${" + WorkerAppItSupport.MONGO_PROPERTY + "}",
    "client.url=http://localhost:3000",
    "ocr.provider=chatgpt",
    "openai.api-key=test-key-not-used",
    "gemini.api-key=",
    "ocr.openai.model=gpt-4.1",
    "ocr.gemini.model=gemini-2.5-flash",
    "ocr.preprocess.mode=none",
    "ocr.preprocess.url=",
    "ocr.preprocess.python-path=",
    "ocr.preprocess.working-dir=",
    "aws.access-key-id=",
    "aws.secret-access-key=",
    "aws.region=us-east-1",
    "upload.token.expiry.minutes=30",
    "messaging.email.api-key=",
    "messaging.email.from-email=",
    "messaging.email.from-name=",
    "shop.cache.enabled=false",
    // the two exit jobs are called directly by the ITs that test them, with the time they choose;
    // their scheduled ticks would race those calls
    "hr.worker-exit.enabled=false",
    "hr.pairing-exit.enabled=false",
    "logging.level.root=WARN",
    "logging.level.com.inventory=WARN"
})
@AutoConfigureMockMvc
public abstract class WorkerAppItSupport {

  /** The replica-set URI, the same property {@code MongoTxIT} reads. */
  public static final String MONGO_PROPERTY = "stockkart.test.mongo.rs.uri";

  static final String AUTH = "/api/v1/auth";
  static final String WORKER = "/api/v1/hr/worker";
  static final String INBOX = "/api/v1/hr/join-requests";
  static final String DEVICE = "/api/v1/hr/attendance-app";
  static final String HR = "/api/v1/hr";

  static final String PASSWORD = "correct-horse-battery";

  static {
    dropDatabaseOnce();
  }

  private static boolean dropped;

  /** Drops the test database once per JVM, before Spring boots and creates its indexes. */
  private static synchronized void dropDatabaseOnce() {
    String uri = System.getProperty(MONGO_PROPERTY);
    if (dropped || uri == null || uri.isBlank()) {
      return;
    }
    dropped = true;
    ConnectionString cs = new ConnectionString(uri);
    String db = cs.getDatabase();
    if (db == null || db.isBlank()) {
      throw new IllegalStateException(MONGO_PROPERTY + " must name a database");
    }
    try (MongoClient client = MongoClients.create(cs)) {
      client.getDatabase(db).drop();
    }
  }

  @Autowired protected MockMvc mvc;

  @Autowired protected MongoTemplate template;

  // ---- people and shops, made through the public API -------------------------------------------

  /** An account with a real session: {@code token}, {@code userId} and the e-mail it signed up with. */
  record Session(String token, String userId, String email) {}

  /** A shop registered by {@code owner}: the shop id, the owner's session and the shop's name. */
  record ShopFixture(String shopId, Session owner, String name) {}

  static String uniqueEmail(String prefix) {
    return prefix + "-" + UUID.randomUUID().toString().substring(0, 8) + "@example.test";
  }

  /** {@code POST /auth/signup} as the Stockkart Team app does it (R1.1, R1.8). */
  Session signupWorker(String name, String phone) throws Exception {
    return signup(name, uniqueEmail("worker"), phone, "WORKER", "worker-app");
  }

  /** {@code POST /auth/signup} as the web does it for a future shop owner or staff member. */
  Session signupStaff(String name, String role) throws Exception {
    return signup(name, uniqueEmail(role.toLowerCase()), "9876500000", role, null);
  }

  Session signup(String name, String email, String phone, String role, String source)
      throws Exception {
    String body = """
        {"name":"%s","email":"%s","password":"%s","phone":"%s","role":"%s"%s}
        """.formatted(name, email, PASSWORD, phone, role,
        source != null ? ",\"source\":\"" + source + "\"" : "");
    MockHttpServletRequestBuilder request = post(AUTH + "/signup")
        .contentType(MediaType.APPLICATION_JSON).content(body);
    if ("worker-app".equals(source)) {
      // every worker signs up from their own phone: a distinct client address each, so the
      // worker-app sign-up limiter (20 an hour per address, R14.1) sees one sign-up per address.
      // The limiter itself is tested in AppRateLimiterWorkerProperties and AuthServiceTest.
      request.with(r -> {
        r.setRemoteAddr(nextClientAddress());
        return r;
      });
    }
    MvcResult r = mvc.perform(request)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.data.accessToken").isString())
        .andExpect(jsonPath("$.data.user.role").value(role))
        .andReturn();
    String json = json(r);
    return new Session(JsonPath.read(json, "$.data.accessToken"),
        JsonPath.read(json, "$.data.user.userId"), email);
  }

  private static final AtomicInteger CLIENT_ADDRESSES = new AtomicInteger();

  /** A fresh RFC 5737 test address for each worker-app sign-up in this JVM. */
  private static String nextClientAddress() {
    int n = CLIENT_ADDRESSES.incrementAndGet();
    return "203.0." + ((n >> 8) & 0xff) + "." + (n & 0xff);
  }

  /** {@code POST /auth/login} with the e-mail and the shared password; a fresh token. */
  Session login(Session account) throws Exception {
    MvcResult r = mvc.perform(post(AUTH + "/login").contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"email":"%s","password":"%s","source":"worker-app"}
                """.formatted(account.email(), PASSWORD)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.accessToken").isString())
        .andReturn();
    return new Session(JsonPath.read(json(r), "$.data.accessToken"), account.userId(),
        account.email());
  }

  /**
   * A new owner and their shop: sign-up, {@code POST /shops/register} (which makes the OWNER
   * membership and sets the account's active shop), then the HR Biometric app switch on so pairing
   * codes can be made and punches accepted.
   */
  ShopFixture newShop(String ownerName, String shopName) throws Exception {
    Session owner = signupStaff(ownerName, "OWNER");
    MvcResult r = mvc.perform(asUser(post("/api/v1/shops/register"), owner)
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"name":"%s","businessId":"BIZ-%s","contactEmail":"%s","contactPhone":"9876500001",
                 "shopType":"RETAILER","verticalId":"grocery",
                 "location":{"primaryAddress":"1 Main Road","state":"Karnataka","city":"Bengaluru",
                             "pin":"560001"}}
                """.formatted(shopName, UUID.randomUUID().toString().substring(0, 8),
                uniqueEmail("shop"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.shopId").isString())
        .andReturn();
    String shopId = JsonPath.read(json(r), "$.data.shopId");
    // the account's token still works: registration only changed the account's shop and role
    mvc.perform(asUser(put(HR + "/settings"), owner)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"attendanceCapture\":{\"biometricApp\":true}}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.attendanceCapture.biometricApp").value(true));
    return new ShopFixture(shopId, owner, shopName);
  }

  /** An employee of the shop inserted directly (a fixture, not the path under test). */
  Employee employee(ShopFixture shop, String code, String name, String userId) {
    Employee e = new Employee();
    e.setShopId(shop.shopId());
    e.setCode(code);
    e.setName(name);
    e.setJoinDate("2025-01-01");
    e.setStatus(EmployeeStatus.ACTIVE);
    e.setUserId(userId);
    e.setCreatedAt(Instant.now());
    e.setUpdatedAt(Instant.now());
    return template.insert(e);
  }

  /**
   * A staff member of the shop with the given role (CASHIER, MANAGER, ADMIN), as the invitation
   * flow would leave them: a signed-up account whose active shop is this one and an active INVITED
   * membership. Both writes go through the mapping events, so the auth cache forgets the account
   * before the next request.
   */
  Session staffMember(ShopFixture shop, String name, UserRole role) throws Exception {
    Session member = signupStaff(name, role.name());
    UserShopMembership membership = new UserShopMembership();
    membership.setUserId(member.userId());
    membership.setShopId(shop.shopId());
    membership.setRole(role);
    membership.setRelationship(UserShopMembershipService.RELATIONSHIP_INVITED);
    membership.setActive(true);
    membership.setJoinedAt(Instant.now());
    template.insert(membership);
    UserAccount account = template.findById(member.userId(), UserAccount.class);
    account.setShopId(shop.shopId());
    template.save(account);
    return member;
  }

  // ---- HTTP helpers ----------------------------------------------------------------------------

  /** The request with the session's real Bearer token; the server resolves the rest. */
  static MockHttpServletRequestBuilder asUser(MockHttpServletRequestBuilder r, Session s) {
    return r.header("Authorization", "Bearer " + s.token());
  }

  static String json(MvcResult result) throws Exception {
    return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
  }

  /** {@code GET /hr/worker/me} as the app does on every focus; asserts the state and returns the body. */
  String me(Session worker, String expectedState) throws Exception {
    return json(mvc.perform(asUser(get(WORKER + "/me"), worker))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.data.state").value(expectedState))
        .andExpect(jsonPath("$.data.account.userId").value(worker.userId()))
        .andReturn());
  }

  /** The envelope refusal shape of {@code GlobalExceptionHandler} for a business {@link ErrorCode}. */
  void expectRefusal(MockHttpServletRequestBuilder request, ErrorCode code) throws Exception {
    mvc.perform(request)
        .andExpect(status().is(code.getHttpStatus().value()))
        .andExpect(jsonPath("$.success").value(false))
        .andExpect(jsonPath("$.error").value(code.getMessage()))
        .andExpect(jsonPath("$.data.message").value(code.getMessage()))
        .andExpect(jsonPath("$.data.status").value(code.getHttpStatus().value()));
  }

  /**
   * A refusal whose text is the thrower's own, not the code's ({@code RbacService} says "You do
   * not have access to this module"): the status and the envelope shape only.
   */
  void expectDenied(MockHttpServletRequestBuilder request, ErrorCode code) throws Exception {
    mvc.perform(request)
        .andExpect(status().is(code.getHttpStatus().value()))
        .andExpect(jsonPath("$.success").value(false))
        .andExpect(jsonPath("$.data.status").value(code.getHttpStatus().value()));
  }

  // ---- the join flow ---------------------------------------------------------------------------

  /** {@code POST /hr/worker/join-requests} for the shop; returns the request id. */
  String join(Session worker, ShopFixture shop) throws Exception {
    MvcResult r = mvc.perform(asUser(post(WORKER + "/join-requests"), worker)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"shopId\":\"" + shop.shopId() + "\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.status").value("PENDING"))
        .andExpect(jsonPath("$.data.kind").doesNotExist())
        .andExpect(jsonPath("$.data.shopName").value(shop.name()))
        .andExpect(jsonPath("$.data.requestedAt").exists())
        .andReturn();
    return JsonPath.read(json(r), "$.data.id");
  }

  /** The owner approves with {@code CREATE}; returns the new employee id. */
  String approveCreate(ShopFixture shop, String requestId) throws Exception {
    MvcResult r = mvc.perform(asUser(post(INBOX + "/" + requestId + "/approve"), shop.owner())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"mode\":\"CREATE\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.id").value(requestId))
        .andExpect(jsonPath("$.data.outcome").value("APPROVED_CREATED"))
        .andExpect(jsonPath("$.data.employeeId").isString())
        .andReturn();
    return JsonPath.read(json(r), "$.data.employeeId");
  }

  /** Sign-up → join → approve: an ACTIVE worker of the shop; returns the worker's session. */
  Session activeWorker(ShopFixture shop, String name, String phone) throws Exception {
    Session worker = signupWorker(name, phone);
    approveCreate(shop, join(worker, shop));
    me(worker, "ACTIVE");
    return worker;
  }

  // ---- pairing: keys, codes and the two handshakes -----------------------------------------------

  /** A phone: an installation id and its two P-256 key pairs, as the app makes them. */
  record Phone(String installationId, KeyPair punchKey, KeyPair deviceKey) {
    static Phone create() throws Exception {
      return new Phone(UUID.randomUUID().toString(), p256(), p256());
    }
  }

  static KeyPair p256() throws Exception {
    KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
    generator.initialize(new ECGenParameterSpec("secp256r1"));
    return generator.generateKeyPair();
  }

  static String spki(KeyPair key) {
    return Base64.getEncoder().encodeToString(key.getPublic().getEncoded());
  }

  /** Base64 DER ECDSA over {@code payload} with the private half of {@code key}. */
  static String sign(KeyPair key, byte[] payload) throws Exception {
    Signature signer = Signature.getInstance("SHA256withECDSA");
    signer.initSign(key.getPrivate());
    signer.update(payload);
    return Base64.getEncoder().encodeToString(signer.sign());
  }

  /** {@code POST /hr/employees/{id}/pairing-codes} as the owner; the clear-text code. */
  String createCode(ShopFixture shop, String employeeId) throws Exception {
    MvcResult r = mvc.perform(asUser(post(HR + "/employees/" + employeeId + "/pairing-codes"),
            shop.owner()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.code").isString())
        .andReturn();
    String code = JsonPath.read(json(r), "$.data.code");
    assertEquals(PairingCodes.qrPayload(code), JsonPath.read(json(r), "$.data.qrPayload"));
    return code;
  }

  static String claimBody(String code, Phone phone) {
    return """
        {"code":"%s","installationId":"%s","os":"ANDROID","osVersion":"14",
         "phoneModel":"Redmi Note 11","appVersion":"2.0.0"}
        """.formatted(code, phone.installationId());
  }

  static String completeBody(String claimToken, Phone phone, int consent) {
    return """
        {"claimToken":"%s","installationId":"%s","punchPublicKey":"%s",
         "devicePublicKey":"%s","consentVersion":%d}
        """.formatted(claimToken, phone.installationId(), spki(phone.punchKey()),
        spki(phone.deviceKey()), consent);
  }

  /**
   * The signed-in handshake (R7.2, R7.3): claim and complete on {@code /hr/worker/pairings/*} with
   * the Bearer token and no envelope header; returns the pairing id.
   */
  String pairWithLogin(Session worker, String code, Phone phone) throws Exception {
    MvcResult claimed = mvc.perform(asUser(post(WORKER + "/pairings/claim"), worker)
            .contentType(MediaType.APPLICATION_JSON).content(claimBody(code, phone)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.claimToken").isString())
        .andExpect(jsonPath("$.data.consent.version").isNumber())
        .andReturn();
    String token = JsonPath.read(json(claimed), "$.data.claimToken");
    int consent = JsonPath.read(json(claimed), "$.data.consent.version");
    MvcResult r = mvc.perform(asUser(post(WORKER + "/pairings/complete"), worker)
            .contentType(MediaType.APPLICATION_JSON).content(completeBody(token, phone, consent)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.pairingId").isString())
        .andReturn();
    return JsonPath.read(json(r), "$.data.pairingId");
  }

  // ---- the device envelope and a signed punch ---------------------------------------------------

  /**
   * The envelope of HR design "Canonical payloads" over {@code request}, signed by the phone's
   * device key, a fresh nonce, now. The signed path and method are taken from the builder itself.
   */
  static MockHttpServletRequestBuilder signed(String pairingId, Phone phone,
      MockHttpServletRequestBuilder request, String query, String body) throws Exception {
    MockHttpServletRequest probe = request.buildRequest(new MockServletContext());
    long timestamp = Instant.now().getEpochSecond();
    String nonce = UUID.randomUUID().toString();
    byte[] bodyBytes = body != null ? body.getBytes(StandardCharsets.UTF_8) : null;
    byte[] payload = AppSignatures.requestEnvelope(new EnvelopeFields(pairingId,
        probe.getMethod(), probe.getRequestURI(), query, timestamp, nonce, bodyBytes));
    if (body != null) {
      request.contentType(MediaType.APPLICATION_JSON).content(bodyBytes);
    }
    return request
        .header(AttendanceAppAuthInterceptor.HEADER_PAIRING_ID, pairingId)
        .header(AttendanceAppAuthInterceptor.HEADER_TIMESTAMP, Long.toString(timestamp))
        .header(AttendanceAppAuthInterceptor.HEADER_NONCE, nonce)
        .header(AttendanceAppAuthInterceptor.HEADER_SIGNATURE, sign(phone.deviceKey(), payload));
  }

  /** The JSON body of an online punch now, its payload signed by the phone's punch key. */
  static String punchBody(String pairingId, String shopId, String employeeId, Phone phone)
      throws Exception {
    String punchId = UUID.randomUUID().toString();
    // the app sends whole seconds (HR canonical payload rule)
    Instant phoneTime = Instant.now().truncatedTo(ChronoUnit.SECONDS);
    PunchFields fields = new PunchFields(pairingId, shopId, employeeId, punchId, phoneTime, false,
        false, null, null, false);
    return """
        {"pairingId":"%s","shopId":"%s","employeeId":"%s","punchId":"%s","phoneTime":"%s",
         "offline":false,"locationChecked":false,"distanceMetres":null,"accuracyMetres":null,
         "mock":false,"signature":"%s"}
        """.formatted(pairingId, shopId, employeeId, punchId,
        DateTimeFormatter.ISO_INSTANT.format(phoneTime),
        sign(phone.punchKey(), AppSignatures.punchPayload(fields)));
  }

  /** A signed {@code POST /attendance-app/punches} with the envelope only, as the app sends it. */
  MockHttpServletRequestBuilder signedPunch(String pairingId, String shopId, String employeeId,
      Phone phone) throws Exception {
    return signed(pairingId, phone, post(DEVICE + "/punches"), null,
        punchBody(pairingId, shopId, employeeId, phone));
  }
}
