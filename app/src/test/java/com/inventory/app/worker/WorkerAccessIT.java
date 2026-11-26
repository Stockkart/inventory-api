package com.inventory.app.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.inventory.common.constants.ErrorCode;
import com.inventory.hr.domain.model.Employee;
import com.inventory.hr.domain.model.Punch;
import com.inventory.hr.domain.model.enums.EmployeeStatus;
import com.inventory.hr.domain.model.enums.PunchDirection;
import com.inventory.hr.domain.model.enums.PunchSource;
import com.inventory.hr.service.AttendanceAppAuthInterceptor;
import com.inventory.hr.utils.HrDateUtils;
import com.jayway.jsonpath.JsonPath;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

/**
 * What a WORKER Bearer token can never do (worker-app R9.8, with R2.1, R7.6, R8.4, R9.1): reach a
 * shop module, read another employee's data, or create a punch. Each case sends the real request
 * through the real chain and expects the access-denied, not-authenticated or two-credentials
 * answer; nothing is set on the request by hand. See {@link WorkerAppItSupport}.
 */
@EnabledIfSystemProperty(named = WorkerAppItSupport.MONGO_PROPERTY, matches = ".+")
class WorkerAccessIT extends WorkerAppItSupport {

  /** A WORKER membership resolves to every module off, HR included (R2.1, DD5). */
  @Test
  void aWorkerTokenReachesNoShopModule() throws Exception {
    ShopFixture shop = newShop("Vikram Shah", "Shah Stores");
    Session worker = activeWorker(shop, "Sunita Devi", "9876500061");

    // the HR staff screens: refused by the RBAC module check, before any handler
    expectDenied(asUser(get(HR + "/employees"), worker), ErrorCode.ACCESS_DENIED);
    expectDenied(asUser(get(HR + "/settings"), worker), ErrorCode.ACCESS_DENIED);
    expectDenied(asUser(get(INBOX), worker), ErrorCode.ACCESS_DENIED);
    expectDenied(asUser(post(HR + "/employees"), worker)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"Someone\"}"),
        ErrorCode.ACCESS_DENIED);
    // the other shop modules
    expectDenied(asUser(get("/api/v1/accounting/accounts"), worker), ErrorCode.ACCESS_DENIED);
    expectDenied(asUser(get("/api/v1/accounting/journal-entries"), worker),
        ErrorCode.ACCESS_DENIED);
    // the owner, for contrast, is let through to the same screens
    mvc.perform(asUser(get(HR + "/employees"), shop.owner())).andExpect(status().isOk());
    mvc.perform(asUser(get("/api/v1/accounting/accounts"), shop.owner()))
        .andExpect(status().isOk());

    // My HR and the worker paths are the only HR paths a WORKER reaches (design "who may do what")
    mvc.perform(asUser(get(HR + "/me"), worker)).andExpect(status().isOk());
    mvc.perform(asUser(get(WORKER + "/me"), worker)).andExpect(status().isOk());
  }

  /** Before approval a WORKER has no shop at all: a staff screen has no shop context to run in. */
  @Test
  void aWorkerWithoutAShopReachesNoStaffScreenEither() throws Exception {
    Session worker = signupWorker("Nikhil Jain", "9876500062");
    for (String path : List.of(HR + "/employees", HR + "/me", INBOX,
        "/api/v1/accounting/accounts")) {
      MvcResult r = mvc.perform(asUser(get(path), worker)).andReturn();
      int status = r.getResponse().getStatus();
      assertTrue(status == 401 || status == 403, path + " answered " + status);
    }
    me(worker, "NO_SHOP");
  }

  /** History is the token's employee's, whatever the query names (R8.4, R11.2). */
  @Test
  void historyAnswersTheCallersOwnPunchesWhateverEmployeeTheQueryNames() throws Exception {
    ShopFixture shop = newShop("Pooja Reddy", "Reddy Stores");
    Session worker = activeWorker(shop, "Arjun Rao", "9876500063");
    Employee mine = template.findOne(Query.query(Criteria.where("userId").is(worker.userId())
        .and("status").is(EmployeeStatus.ACTIVE)), Employee.class);
    Employee other = employee(shop, "EMP-OTHER", "Other Person", null);
    // all three punches lie in today (Asia/Kolkata), so the from=today&to=today query below sees
    // the worker's own one even when the test runs just after midnight IST
    Instant now = Instant.now();
    Instant startOfToday = HrDateUtils.today().atStartOfDay(HrDateUtils.ZONE).toInstant();
    Instant earliest = now.minus(2, ChronoUnit.HOURS).isBefore(startOfToday)
        ? startOfToday : now.minus(2, ChronoUnit.HOURS);
    storedPunch(shop, mine, earliest);
    storedPunch(shop, other, earliest.plus(1, ChronoUnit.MINUTES));
    storedPunch(shop, other, earliest.plus(2, ChronoUnit.MINUTES));

    for (String query : List.of("employeeId=" + other.getId(),
        "employeeId=" + other.getId() + "&shopId=" + shop.shopId(),
        "from=" + today() + "&to=" + today() + "&employeeId=" + other.getId())) {
      MvcResult r = mvc.perform(asUser(get(WORKER + "/punches?" + query), worker))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.data.length()").value(1))
          .andReturn();
      List<String> employees = JsonPath.read(json(r), "$.data[*].employeeId");
      assertEquals(List.of(mine.getId()), employees, query);
    }
    // the same holds for My HR's own reads
    mvc.perform(asUser(get(HR + "/me").param("employeeId", other.getId()), worker))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.id").value(mine.getId()))
        .andExpect(jsonPath("$.data.code").value(mine.getCode()));
  }

  /** A punch is a device call: a Bearer token on it is refused before anything is read (R7.6, R9.1). */
  @Test
  void aPunchWithABearerTokenIsRefusedAsTwoCredentials() throws Exception {
    ShopFixture shop = newShop("Harish Gupta", "Gupta Stores");
    Session worker = activeWorker(shop, "Neha Kapoor", "9876500064");
    Employee mine = template.findOne(Query.query(Criteria.where("userId").is(worker.userId())
        .and("status").is(EmployeeStatus.ACTIVE)), Employee.class);
    Phone phone = Phone.create();
    String pairingId = pairWithLogin(worker, createCode(shop, mine.getId()), phone);
    long before = punchCount(shop);

    // Bearer alone: no envelope to verify, still refused as two credentials, not as unsigned
    expectRefusal(asUser(post(DEVICE + "/punches"), worker)
            .contentType(MediaType.APPLICATION_JSON)
            .content(punchBody(pairingId, shop.shopId(), mine.getId(), phone)),
        ErrorCode.HR_TWO_CREDENTIALS);
    // Bearer and a perfectly good envelope: refused all the same, before the signature is checked
    expectRefusal(asUser(signedPunch(pairingId, shop.shopId(), mine.getId(), phone), worker),
        ErrorCode.HR_TWO_CREDENTIALS);
    // the mirror: a worker call carrying an envelope header is refused before the token is read
    expectRefusal(asUser(get(WORKER + "/me"), worker)
            .header(AttendanceAppAuthInterceptor.HEADER_PAIRING_ID, pairingId),
        ErrorCode.HR_TWO_CREDENTIALS);
    expectRefusal(asUser(get(WORKER + "/punches"), worker)
            .header(AttendanceAppAuthInterceptor.HEADER_SIGNATURE, "MEUCIQ"),
        ErrorCode.HR_TWO_CREDENTIALS);
    assertEquals(before, punchCount(shop), "nothing was stored");

    // the same punch, envelope only, is accepted: the token is what was wrong (R13.1)
    mvc.perform(signedPunch(pairingId, shop.shopId(), mine.getId(), phone))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.outcome").value("STORED"));
    assertEquals(before + 1, punchCount(shop));
  }

  /** A bad or missing token is "not authenticated", never a quiet empty answer (R9.3). */
  @Test
  void aMissingOrUnknownTokenIsNotAuthenticated() throws Exception {
    mvc.perform(get(WORKER + "/me")).andExpect(status().isUnauthorized());
    mvc.perform(get(WORKER + "/me").header("Authorization", "Bearer not-a-real-token"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.success").value(false));
    mvc.perform(post(WORKER + "/leave").header("Authorization", "Bearer not-a-real-token"))
        .andExpect(status().isUnauthorized());
  }

  // ---- helpers -----------------------------------------------------------------------------------

  private long punchCount(ShopFixture shop) {
    return template.count(Query.query(Criteria.where("shopId").is(shop.shopId())), Punch.class);
  }

  /** A stored app punch at {@code at} for history tests; the shape {@code AppPunchService} writes. */
  private Punch storedPunch(ShopFixture shop, Employee e, Instant at) {
    Punch p = new Punch();
    p.setShopId(shop.shopId());
    p.setEmployeeId(e.getId());
    p.setAt(at);
    p.setLocalDate(HrDateUtils.format(at.atZone(HrDateUtils.ZONE).toLocalDate()));
    p.setSource(PunchSource.BIOMETRIC_APP);
    p.setDirection(PunchDirection.IN);
    p.setFlags(new ArrayList<>());
    p.setDedupeKey("B:" + e.getId() + ":" + UUID.randomUUID());
    p.setDeviceModel("Redmi Note 11");
    p.setPhoneTime(at);
    p.setCreatedAt(at);
    return template.insert(p);
  }

  private static String today() {
    return HrDateUtils.format(HrDateUtils.today());
  }
}
