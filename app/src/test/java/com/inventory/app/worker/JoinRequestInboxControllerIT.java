package com.inventory.app.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.inventory.common.constants.ErrorCode;
import com.inventory.hr.domain.model.DevicePairing;
import com.inventory.hr.domain.model.Employee;
import com.inventory.hr.domain.model.HrAuditEvent;
import com.inventory.hr.domain.model.HrNotification;
import com.inventory.hr.domain.model.WorkerJoinRequest;
import com.inventory.hr.domain.model.enums.EmployeeStatus;
import com.inventory.hr.domain.model.enums.HrAuditAction;
import com.inventory.hr.domain.model.enums.HrNotificationType;
import com.inventory.hr.domain.model.enums.JoinOutcome;
import com.inventory.hr.domain.model.enums.PairingStatus;
import com.inventory.hr.domain.model.enums.WorkerJoinStatus;
import com.inventory.hr.service.PairingExitJob;
import com.inventory.hr.service.WorkerExitJob;
import com.inventory.hr.utils.HrDateUtils;
import com.inventory.user.domain.model.UserAccount;
import com.inventory.user.domain.model.UserRole;
import com.inventory.user.domain.model.UserShopMembership;
import com.jayway.jsonpath.JsonPath;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * The manager's Join requests tab against the real application and a real MongoDB (worker-app task
 * 4.10, checkpoint 5; requirements 3.5, 3.6, 4.1–4.13, 5.2, 5.3, 8.7, 10.1–10.3, 13.5, 14.4): the
 * tab count and the newest-first pages, the possible match by e-mail or normalised phone, approve
 * as create or link with the two link refusals word for word, reject with a reason the worker then
 * reads, the bulk actions and their partition of the selection, who may not use the inbox at all,
 * "Request profile details", withdraw, the one-open-request rule under a race, and the exit job's
 * two sweeps. Every request carries a real Bearer token; see {@link WorkerAppItSupport}.
 */
@EnabledIfSystemProperty(named = WorkerAppItSupport.MONGO_PROPERTY, matches = ".+")
class JoinRequestInboxControllerIT extends WorkerAppItSupport {

  @Autowired private WorkerExitJob exitJob;

  @Autowired private PairingExitJob pairingExitJob;

  // ---- the tab: count, order, pages (R4.1, R4.2, R11.3) -------------------------------------------

  @Test
  void theTabCountsPendingRequestsAndListsThemNewestFirstInPages() throws Exception {
    ShopFixture shop = newShop("Ramesh Kumar", "Sri Ganesh Stores");
    Session first = signupWorker("Priya Sharma", "9876500101");
    String r1 = join(first, shop);
    Session second = signupWorker("Ravi Kumar", "+91 98765 00102");
    String r2 = join(second, shop);
    Session third = signupWorker("Kavya Nair", "9876500103");
    String r3 = join(third, shop);

    // the default page: everything pending, newest first
    MvcResult all = mvc.perform(asUser(get(INBOX), shop.owner()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.pendingCount").value(3))
        .andExpect(jsonPath("$.data.page").value(0))
        .andExpect(jsonPath("$.data.size").value(50))
        .andExpect(jsonPath("$.data.totalItems").value(3))
        .andExpect(jsonPath("$.data.totalPages").value(1))
        .andExpect(jsonPath("$.data.items.length()").value(3))
        .andExpect(jsonPath("$.data.items[0].id").value(r3))
        .andExpect(jsonPath("$.data.items[1].id").value(r2))
        .andExpect(jsonPath("$.data.items[2].id").value(r1))
        .andExpect(jsonPath("$.data.items[1].name").value("Ravi Kumar"))
        .andExpect(jsonPath("$.data.items[1].phone").value("9876500102"))
        .andExpect(jsonPath("$.data.items[1].email").value(second.email()))
        .andExpect(jsonPath("$.data.items[1].kind").doesNotExist())
        .andExpect(jsonPath("$.data.items[1].status").value("PENDING"))
        .andExpect(jsonPath("$.data.items[1].requestedAt").isString())
        .andExpect(jsonPath("$.data.items[1].hasPossibleMatch").value(false))
        .andExpect(jsonPath("$.data.items[1].photoUrl").doesNotExist())
        .andExpect(jsonPath("$.data.items[1].pairedEmployee").doesNotExist())
        .andReturn();
    // R4.2: no other personal data travels in a row
    Map<String, Object> row = JsonPath.read(json(all), "$.data.items[1]");
    assertTrue(Set.of("id", "name", "phone", "email", "photoUrl", "requestedAt", "status",
        "hasPossibleMatch").containsAll(row.keySet()), row.keySet().toString());

    // pages of two: the newest two, then the oldest one
    mvc.perform(asUser(get(INBOX).param("status", "PENDING").param("page", "0")
            .param("size", "2"), shop.owner()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.pendingCount").value(3))
        .andExpect(jsonPath("$.data.page").value(0))
        .andExpect(jsonPath("$.data.size").value(2))
        .andExpect(jsonPath("$.data.totalItems").value(3))
        .andExpect(jsonPath("$.data.totalPages").value(2))
        .andExpect(jsonPath("$.data.items.length()").value(2))
        .andExpect(jsonPath("$.data.items[0].id").value(r3))
        .andExpect(jsonPath("$.data.items[1].id").value(r2));
    mvc.perform(asUser(get(INBOX).param("page", "1").param("size", "2"), shop.owner()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.page").value(1))
        .andExpect(jsonPath("$.data.items.length()").value(1))
        .andExpect(jsonPath("$.data.items[0].id").value(r1));

    // the Rejected tab is empty but still carries the pending count for the tab label
    mvc.perform(asUser(get(INBOX).param("status", "rejected"), shop.owner()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.items.length()").value(0))
        .andExpect(jsonPath("$.data.totalItems").value(0))
        .andExpect(jsonPath("$.data.pendingCount").value(3));

    // a rejection moves one row from one tab to the other and the count follows
    mvc.perform(reject(shop, r2, "{}")).andExpect(status().isOk());
    mvc.perform(asUser(get(INBOX).param("status", "REJECTED"), shop.owner()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.items.length()").value(1))
        .andExpect(jsonPath("$.data.items[0].id").value(r2))
        .andExpect(jsonPath("$.data.items[0].status").value("REJECTED"))
        .andExpect(jsonPath("$.data.pendingCount").value(2));
    mvc.perform(asUser(get(INBOX), shop.owner()))
        .andExpect(jsonPath("$.data.pendingCount").value(2))
        .andExpect(jsonPath("$.data.items.length()").value(2))
        .andExpect(jsonPath("$.data.items[0].id").value(r3))
        .andExpect(jsonPath("$.data.items[1].id").value(r1));

    // shape errors are 400s with the validator's text (design: validation is for shapes only)
    for (MockHttpServletRequestBuilder bad : List.of(
        get(INBOX).param("size", "201"),
        get(INBOX).param("size", "0"),
        get(INBOX).param("page", "-1"),
        get(INBOX + "/not-an-id"))) {
      mvc.perform(asUser(bad, shop.owner()))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.success").value(false));
    }
    mvc.perform(asUser(get(INBOX).param("status", "APPROVED"), shop.owner()))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("status must be PENDING or REJECTED"));
    mvc.perform(asUser(get(INBOX).param("size", "200"), shop.owner())).andExpect(status().isOk());
  }

  // ---- the possible match (R4.3) ----------------------------------------------------------------

  @Test
  void theDetailNamesThePossibleMatchByEmailOrNormalisedPhoneWithItsStatusAndLink() throws Exception {
    ShopFixture shop = newShop("Meena Iyer", "Iyer Provisions");
    ShopFixture otherShop = newShop("Someone Else", "Other Stores");
    Session someoneElse = signupWorker("Someone Else", "9876500110");

    Session byEmail = signupWorker("Priya Sharma", "9876500111");
    Employee priya = employee(shop, "EMP-101", "Priya S", null);
    // the employee's e-mail differs in case and spacing from the account's
    priya.setEmail("  " + byEmail.email().toUpperCase() + " ");
    template.save(priya);

    Session byPhone = signupWorker("Ravi Kumar", "9876500112");
    Employee ravi = employee(shop, "EMP-102", "Ravi K", null);
    ravi.setPhone("+91 98765 00112");
    ravi.setStatus(EmployeeStatus.EXITED);
    ravi.setExitDate("2025-06-30");
    template.save(ravi);

    Session byPhoneLinked = signupWorker("Mohan Das", "+91 98765-00113");
    Employee mohan = employee(shop, "EMP-103", "Mohan D", someoneElse.userId());
    mohan.setPhone("98765 00113");
    template.save(mohan);

    Session noMatch = signupWorker("Kavya Nair", "9876500114");
    // the same phone as Kavya, but in another shop: not a match here
    Employee elsewhere = employee(otherShop, "EMP-901", "Kavya Elsewhere", null);
    elsewhere.setPhone("9876500114");
    template.save(elsewhere);

    String emailRequest = join(byEmail, shop);
    String phoneRequest = join(byPhone, shop);
    String linkedRequest = join(byPhoneLinked, shop);
    String plainRequest = join(noMatch, shop);

    mvc.perform(asUser(get(INBOX + "/" + emailRequest), shop.owner()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.id").value(emailRequest))
        .andExpect(jsonPath("$.data.name").value("Priya Sharma"))
        .andExpect(jsonPath("$.data.hasPossibleMatch").value(true))
        .andExpect(jsonPath("$.data.possibleMatch.employeeId").value(priya.getId()))
        .andExpect(jsonPath("$.data.possibleMatch.code").value("EMP-101"))
        .andExpect(jsonPath("$.data.possibleMatch.name").value("Priya S"))
        .andExpect(jsonPath("$.data.possibleMatch.status").value("ACTIVE"))
        .andExpect(jsonPath("$.data.possibleMatch.linked").value(false));
    mvc.perform(asUser(get(INBOX + "/" + phoneRequest), shop.owner()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.hasPossibleMatch").value(true))
        .andExpect(jsonPath("$.data.possibleMatch.employeeId").value(ravi.getId()))
        .andExpect(jsonPath("$.data.possibleMatch.code").value("EMP-102"))
        .andExpect(jsonPath("$.data.possibleMatch.status").value("EXITED"))
        .andExpect(jsonPath("$.data.possibleMatch.linked").value(false));
    mvc.perform(asUser(get(INBOX + "/" + linkedRequest), shop.owner()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.phone").value("9876500113"))
        .andExpect(jsonPath("$.data.hasPossibleMatch").value(true))
        .andExpect(jsonPath("$.data.possibleMatch.employeeId").value(mohan.getId()))
        .andExpect(jsonPath("$.data.possibleMatch.status").value("ACTIVE"))
        .andExpect(jsonPath("$.data.possibleMatch.linked").value(true));
    mvc.perform(asUser(get(INBOX + "/" + plainRequest), shop.owner()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.hasPossibleMatch").value(false))
        .andExpect(jsonPath("$.data.possibleMatch").doesNotExist());

    // the list carries the flag for every row
    MvcResult list = mvc.perform(asUser(get(INBOX), shop.owner()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.items.length()").value(4))
        .andReturn();
    for (Map<String, Object> row : JsonPath.<List<Map<String, Object>>>read(json(list),
        "$.data.items")) {
      boolean expected = !plainRequest.equals(row.get("id"));
      assertEquals(expected, row.get("hasPossibleMatch"), row.get("name").toString());
    }

    // another shop's approver cannot read this shop's request (tenant scoping)
    mvc.perform(asUser(get(INBOX + "/" + emailRequest), otherShop.owner()))
        .andExpect(status().isNotFound());
    mvc.perform(asUser(get(INBOX), otherShop.owner()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.pendingCount").value(0))
        .andExpect(jsonPath("$.data.items.length()").value(0));
  }

  // ---- approve: create or link, and the link refusals (R4.4, R4.5, R4.8, R4.9, R4.10, R14.4) -----

  @Test
  void approveLinksTheChosenEmployeeAndRefusesALinkedOrExitedOneWordForWord() throws Exception {
    ShopFixture shop = newShop("Lakshmi Devi", "Devi General Store");
    Session otherLogin = signupWorker("Other Login", "9876500120");
    Session priya = signupWorker("Priya Sharma", "9876500121");
    Employee match = employee(shop, "EMP-201", "Priya S", null);
    match.setPhone("+91 98765 00121");
    template.save(match);
    Employee exited = employee(shop, "EMP-202", "Gone Person", null);
    exited.setStatus(EmployeeStatus.EXITED);
    exited.setExitDate("2025-03-31");
    template.save(exited);
    Employee linkedElsewhere = employee(shop, "EMP-203", "Taken Person", otherLogin.userId());
    long employeesBefore = employees(shop);

    String requestId = join(priya, shop);
    mvc.perform(asUser(get(INBOX + "/" + requestId), shop.owner()))
        .andExpect(jsonPath("$.data.possibleMatch.employeeId").value(match.getId()));

    // "Link to existing" on an employee linked to another login (R4.5)
    mvc.perform(approve(shop, requestId, linkBody(linkedElsewhere.getId())))
        .andExpect(status().is(ErrorCode.HR_MATCH_LINKED_ELSEWHERE.getHttpStatus().value()))
        .andExpect(jsonPath("$.success").value(false))
        .andExpect(jsonPath("$.error").value("EMP-203 is already linked to another login"));
    assertNothingChanged(shop, priya, requestId, employeesBefore);
    assertEquals(otherLogin.userId(),
        template.findById(linkedElsewhere.getId(), Employee.class).getUserId());

    // "Link to existing" on an EXITED employee (R4.5)
    mvc.perform(approve(shop, requestId, linkBody(exited.getId())))
        .andExpect(status().is(ErrorCode.HR_MATCH_EXITED.getHttpStatus().value()))
        .andExpect(jsonPath("$.success").value(false))
        .andExpect(jsonPath("$.error").value("EMP-202 has exited. Create a new employee instead."));
    assertNothingChanged(shop, priya, requestId, employeesBefore);
    assertNull(template.findById(exited.getId(), Employee.class).getUserId());

    // an employee that is not this shop's, and the shape errors
    mvc.perform(approve(shop, requestId, linkBody("0123456789abcdef01234567")))
        .andExpect(status().isNotFound());
    mvc.perform(approve(shop, requestId, "{\"mode\":\"LINK\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("employeeId is required to link"));
    mvc.perform(approve(shop, requestId, "{\"mode\":\"MERGE\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("mode must be CREATE or LINK"));
    mvc.perform(approve(shop, requestId, "{}"))
        .andExpect(status().isBadRequest());
    assertNothingChanged(shop, priya, requestId, employeesBefore);

    // "Link to existing" with the match (R4.9): userId only, everything else as it was
    mvc.perform(approve(shop, requestId, linkBody(match.getId())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.id").value(requestId))
        .andExpect(jsonPath("$.data.outcome").value("APPROVED_LINKED"))
        .andExpect(jsonPath("$.data.employeeId").value(match.getId()));
    Employee linked = template.findById(match.getId(), Employee.class);
    assertEquals(priya.userId(), linked.getUserId());
    assertEquals("EMP-201", linked.getCode());
    assertEquals("Priya S", linked.getName(), "the employee keeps its own name");
    assertEquals("+91 98765 00121", linked.getPhone(), "the employee keeps its own phone");
    assertEquals("2025-01-01", linked.getJoinDate());
    assertEquals(employeesBefore, employees(shop), "no employee was created");
    WorkerJoinRequest approved = template.findById(requestId, WorkerJoinRequest.class);
    assertEquals(WorkerJoinStatus.APPROVED, approved.getStatus());
    assertEquals(JoinOutcome.APPROVED_LINKED, approved.getOutcome());
    assertEquals(match.getId(), approved.getEmployeeId());
    assertEquals(shop.owner().userId(), approved.getDecidedByUserId());
    assertNotNull(approved.getDecidedAt());
    assertRetention(approved);
    assertEquals(shop.shopId(), template.findById(priya.userId(), UserAccount.class).getShopId());
    List<UserShopMembership> memberships = memberships(priya);
    assertEquals(1, memberships.size());
    assertEquals(UserRole.WORKER, memberships.get(0).getRole());
    assertTrue(memberships.get(0).isActive());
    // one audit row naming approver, request, employee and outcome (R4.11)
    List<HrAuditEvent> audit = audit(shop, HrAuditAction.JOIN_REQUEST_APPROVED);
    assertEquals(1, audit.size());
    assertEquals(shop.owner().userId(), audit.get(0).getActorUserId());
    assertEquals(requestId, audit.get(0).getEntityId());
    assertEquals(match.getId(), audit.get(0).getEmployeeId());
    assertEquals("APPROVED_LINKED", audit.get(0).getAfter().get("outcome"));
    assertEquals("LINK", audit.get(0).getAfter().get("mode"));
    // one notification to the worker, with the shop's name and nothing about contact data (R4.13)
    List<HrNotification> told = notifications(priya, HrNotificationType.JOIN_REQUEST_APPROVED);
    assertEquals(1, told.size());
    assertEquals(requestId, told.get(0).getJoinRequestId());
    assertEquals("Devi General Store accepted you", told.get(0).getText());
    String home = me(priya, "ACTIVE");
    assertEquals(match.getId(), JsonPath.read(home, "$.data.employee.employeeId"));
    assertEquals("EMP-201", JsonPath.read(home, "$.data.employee.code"));

    // the request is decided: a second approve, in either mode, and a reject are refused (R4.10)
    expectRefusal(approve(shop, requestId, linkBody(match.getId())),
        ErrorCode.HR_REQUEST_ALREADY_HANDLED);
    expectRefusal(approve(shop, requestId, "{\"mode\":\"CREATE\"}"),
        ErrorCode.HR_REQUEST_ALREADY_HANDLED);
    expectRefusal(reject(shop, requestId, "{\"reason\":\"too late\"}"),
        ErrorCode.HR_REQUEST_ALREADY_HANDLED);
    assertEquals(employeesBefore, employees(shop));

    // "Create new" for another worker, with the match left alone (R4.8)
    Session ravi = signupWorker("Ravi Kumar", "9876500122");
    String raviRequest = join(ravi, shop);
    String created = approveCreate(shop, raviRequest);
    Employee fresh = template.findById(created, Employee.class);
    assertEquals(ravi.userId(), fresh.getUserId());
    assertEquals("Ravi Kumar", fresh.getName());
    assertEquals("9876500122", fresh.getPhone());
    assertEquals(ravi.email(), fresh.getEmail());
    assertEquals(today(), fresh.getJoinDate());
    assertEquals(EmployeeStatus.ACTIVE, fresh.getStatus());
    assertNull(fresh.getDesignation());
    assertEquals(employeesBefore + 1, employees(shop));
    assertEquals(JoinOutcome.APPROVED_CREATED,
        template.findById(raviRequest, WorkerJoinRequest.class).getOutcome());
    assertEquals("APPROVED_CREATED", audit(shop, HrAuditAction.JOIN_REQUEST_APPROVED).stream()
        .filter(a -> raviRequest.equals(a.getEntityId())).findFirst().orElseThrow()
        .getAfter().get("outcome"));
  }

  // ---- reject with a reason (R4.7, R4.10–R4.13, R10.2, R10.3) -------------------------------------

  @Test
  void rejectKeepsTheTrimmedReasonAndTheWorkerReadsItOnTheNextRefresh() throws Exception {
    ShopFixture shop = newShop("Suresh Rao", "Rao Traders");
    Session priya = signupWorker("Priya Sharma", "9876500131");
    String requestId = join(priya, shop);

    // 201 characters after trimming: refused, nothing decided
    String tooLong = "x".repeat(201);
    mvc.perform(reject(shop, requestId, "{\"reason\":\"" + tooLong + "\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.success").value(false))
        .andExpect(jsonPath("$.error").value("The reason must be at most 200 characters"));
    assertEquals(WorkerJoinStatus.PENDING,
        template.findById(requestId, WorkerJoinRequest.class).getStatus());
    me(priya, "PENDING");

    // a reason with spaces around it is stored trimmed
    mvc.perform(reject(shop, requestId, "{\"reason\":\"   Not hiring right now  \"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.id").value(requestId))
        .andExpect(jsonPath("$.data.outcome").value("REJECTED"))
        .andExpect(jsonPath("$.data.employeeId").doesNotExist());
    WorkerJoinRequest rejected = template.findById(requestId, WorkerJoinRequest.class);
    assertEquals(WorkerJoinStatus.REJECTED, rejected.getStatus());
    assertEquals(JoinOutcome.REJECTED, rejected.getOutcome());
    assertEquals("Not hiring right now", rejected.getRejectionReason());
    assertEquals(shop.owner().userId(), rejected.getDecidedByUserId());
    assertNotNull(rejected.getDecidedAt());
    assertNull(rejected.getEmployeeId());
    assertRetention(rejected);
    assertNull(template.findById(priya.userId(), UserAccount.class).getShopId());
    assertEquals(0, memberships(priya).size());
    assertEquals(0, template.find(Query.query(Criteria.where("userId").is(priya.userId())),
        Employee.class).size());

    // one audit row with the reason (R4.11)
    List<HrAuditEvent> audit = audit(shop, HrAuditAction.JOIN_REQUEST_REJECTED);
    assertEquals(1, audit.size());
    assertEquals(shop.owner().userId(), audit.get(0).getActorUserId());
    assertEquals(requestId, audit.get(0).getEntityId());
    assertEquals("Not hiring right now", audit.get(0).getReason());
    assertEquals("REJECTED", audit.get(0).getAfter().get("outcome"));

    // the worker's next refresh: Join a shop again, with the outcome and the reason (R4.12)
    String state = me(priya, "NO_SHOP");
    assertEquals(requestId, JsonPath.read(state, "$.data.lastOutcome.id"));
    assertEquals("REJECTED", JsonPath.read(state, "$.data.lastOutcome.status"));
    assertEquals("Rao Traders", JsonPath.read(state, "$.data.lastOutcome.shopName"));
    assertEquals("Not hiring right now", JsonPath.read(state, "$.data.lastOutcome.reason"));
    assertNotNull(JsonPath.read(state, "$.data.lastOutcome.decidedAt"));
    assertEquals(1, ((Number) JsonPath.read(state, "$.data.notifications.unread")).intValue());
    assertEquals("JOIN_REQUEST_REJECTED",
        JsonPath.read(state, "$.data.notifications.top[0].type"));
    assertEquals("Rao Traders did not accept your request: Not hiring right now",
        JsonPath.read(state, "$.data.notifications.top[0].text"));
    assertEquals(requestId, JsonPath.read(state, "$.data.notifications.top[0].joinRequestId"));
    MvcResult inbox = mvc.perform(asUser(get(WORKER + "/notifications"), priya))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.unread").value(1))
        .andExpect(jsonPath("$.data.items[0].type").value("JOIN_REQUEST_REJECTED"))
        .andExpect(jsonPath("$.data.items[0].read").value(false))
        .andReturn();
    String notificationId = JsonPath.read(json(inbox), "$.data.items[0].id");
    for (String secret : List.of(priya.email(), "9876500131")) {
      assertFalse(JsonPath.<String>read(json(inbox), "$.data.items[0].text").contains(secret),
          "contact data stays out of the text (R10.3)");
    }
    mvc.perform(asUser(post(WORKER + "/notifications/" + notificationId + "/read"), priya))
        .andExpect(status().isOk());
    mvc.perform(asUser(get(WORKER + "/notifications"), priya))
        .andExpect(jsonPath("$.data.unread").value(0))
        .andExpect(jsonPath("$.data.items[0].read").value(true));

    // decided is decided (R4.10)
    expectRefusal(reject(shop, requestId, "{\"reason\":\"again\"}"),
        ErrorCode.HR_REQUEST_ALREADY_HANDLED);
    expectRefusal(approve(shop, requestId, "{\"mode\":\"CREATE\"}"),
        ErrorCode.HR_REQUEST_ALREADY_HANDLED);
    assertEquals("Not hiring right now",
        template.findById(requestId, WorkerJoinRequest.class).getRejectionReason());

    // exactly 200 characters is fine; blank and absent reasons are stored as none
    Session ravi = signupWorker("Ravi Kumar", "9876500132");
    String raviRequest = join(ravi, shop);
    String longest = "y".repeat(200);
    mvc.perform(reject(shop, raviRequest, "{\"reason\":\"" + longest + "\"}"))
        .andExpect(status().isOk());
    assertEquals(longest,
        template.findById(raviRequest, WorkerJoinRequest.class).getRejectionReason());

    Session kavya = signupWorker("Kavya Nair", "9876500133");
    String kavyaRequest = join(kavya, shop);
    mvc.perform(reject(shop, kavyaRequest, "{\"reason\":\"    \"}")).andExpect(status().isOk());
    assertNull(template.findById(kavyaRequest, WorkerJoinRequest.class).getRejectionReason());
    String kavyaState = me(kavya, "NO_SHOP");
    assertEquals("REJECTED", JsonPath.read(kavyaState, "$.data.lastOutcome.status"));
    assertNull(JsonPath.read(kavyaState, "$.data.lastOutcome.reason"));
    assertEquals("Rao Traders did not accept your request",
        JsonPath.read(kavyaState, "$.data.notifications.top[0].text"));

    Session mohan = signupWorker("Mohan Das", "9876500134");
    String mohanRequest = join(mohan, shop);
    mvc.perform(asUser(post(INBOX + "/" + mohanRequest + "/reject"), shop.owner()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.outcome").value("REJECTED"));
    assertNull(template.findById(mohanRequest, WorkerJoinRequest.class).getRejectionReason());

    // the Rejected tab shows all four, newest decision first by request time
    mvc.perform(asUser(get(INBOX).param("status", "REJECTED"), shop.owner()))
        .andExpect(jsonPath("$.data.items.length()").value(4))
        .andExpect(jsonPath("$.data.pendingCount").value(0));
  }

  // ---- bulk actions (R4.6, R14.4) ---------------------------------------------------------------

  @Test
  void bulkApprovePartitionsTheSelectionIntoApprovedSkippedAndFailed() throws Exception {
    ShopFixture shop = newShop("Anil Mehta", "Mehta Stores");
    ShopFixture otherShop = newShop("Geeta Patel", "Patel Mart");
    Session plainA = signupWorker("Asha Verma", "9876500141");
    Session matched = signupWorker("Priya Sharma", "9876500142");
    Employee match = employee(shop, "EMP-301", "Priya S", null);
    match.setEmail(matched.email());
    template.save(match);
    Session alreadyRejected = signupWorker("Rejected Person", "9876500143");
    Session plainB = signupWorker("Deepa Singh", "9876500144");
    Session withdrawn = signupWorker("Withdrawn Person", "9876500145");
    Session elsewhere = signupWorker("Other Shop Person", "9876500146");

    String a = join(plainA, shop);
    String m = join(matched, shop);
    String r = join(alreadyRejected, shop);
    String b = join(plainB, shop);
    String w = join(withdrawn, shop);
    String o = join(elsewhere, otherShop);
    mvc.perform(reject(shop, r, "{}")).andExpect(status().isOk());
    mvc.perform(asUser(post(WORKER + "/join-requests/" + w + "/withdraw"), withdrawn))
        .andExpect(status().isOk());
    long employeesBefore = employees(shop);

    MvcResult result = mvc.perform(asUser(post(INBOX + "/bulk/approve"), shop.owner())
            .contentType(MediaType.APPLICATION_JSON)
            .content(ids(a, m, r, b, w, o, a)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.approved").value(2))
        .andExpect(jsonPath("$.data.skipped.length()").value(1))
        .andExpect(jsonPath("$.data.skipped[0].id").value(m))
        .andExpect(jsonPath("$.data.skipped[0].reason").value("POSSIBLE_MATCH"))
        .andExpect(jsonPath("$.data.failed.length()").value(3))
        .andReturn();
    Map<String, String> failed = failedById(json(result));
    assertEquals(ErrorCode.HR_REQUEST_ALREADY_HANDLED.getMessage(), failed.get(r));
    assertEquals(ErrorCode.HR_REQUEST_ALREADY_HANDLED.getMessage(), failed.get(w));
    assertEquals("Join request not found with id: " + o, failed.get(o));

    // the two plain requests are approved, each with its own employee; the rest untouched
    for (String id : List.of(a, b)) {
      WorkerJoinRequest req = template.findById(id, WorkerJoinRequest.class);
      assertEquals(WorkerJoinStatus.APPROVED, req.getStatus());
      assertEquals(JoinOutcome.APPROVED_CREATED, req.getOutcome());
      assertNotNull(req.getEmployeeId());
    }
    assertEquals(employeesBefore + 2, employees(shop));
    assertEquals(WorkerJoinStatus.PENDING, template.findById(m, WorkerJoinRequest.class).getStatus(),
        "the possible match is left for the approver");
    assertNull(template.findById(match.getId(), Employee.class).getUserId());
    assertEquals(WorkerJoinStatus.REJECTED, template.findById(r, WorkerJoinRequest.class).getStatus());
    assertEquals(WorkerJoinStatus.WITHDRAWN,
        template.findById(w, WorkerJoinRequest.class).getStatus());
    assertEquals(WorkerJoinStatus.PENDING, template.findById(o, WorkerJoinRequest.class).getStatus(),
        "another shop's request is never touched");
    me(plainA, "ACTIVE");
    me(plainB, "ACTIVE");
    me(matched, "PENDING");
    mvc.perform(asUser(get(INBOX), shop.owner()))
        .andExpect(jsonPath("$.data.pendingCount").value(1))
        .andExpect(jsonPath("$.data.items[0].id").value(m));
    // one audit row per approval (R4.11)
    assertEquals(2, audit(shop, HrAuditAction.JOIN_REQUEST_APPROVED).size());

    // the bounds of a selection
    List<String> tooMany = new ArrayList<>();
    for (int i = 0; i < 51; i++) {
      tooMany.add(String.format("%024x", i + 1));
    }
    mvc.perform(asUser(post(INBOX + "/bulk/approve"), shop.owner())
            .contentType(MediaType.APPLICATION_JSON)
            .content(ids(tooMany.toArray(String[]::new))))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("Select at most 50 requests at a time"));
    mvc.perform(asUser(post(INBOX + "/bulk/approve"), shop.owner())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"ids\":[]}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("Select at least one request"));
    mvc.perform(asUser(post(INBOX + "/bulk/approve"), shop.owner())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"ids\":[\"nope\"]}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void bulkRejectAppliesTheOneReasonToEverySelectedRequest() throws Exception {
    ShopFixture shop = newShop("Vikram Shah", "Shah Stores");
    Session one = signupWorker("Asha Verma", "9876500151");
    Session two = signupWorker("Deepa Singh", "9876500152");
    Session three = signupWorker("Kavya Nair", "9876500153");
    Session done = signupWorker("Already In", "9876500154");
    String r1 = join(one, shop);
    String r2 = join(two, shop);
    String r3 = join(three, shop);
    String r4 = join(done, shop);
    approveCreate(shop, r4);

    mvc.perform(asUser(post(INBOX + "/bulk/reject"), shop.owner())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"ids\":[\"%s\",\"%s\",\"%s\",\"%s\"],\"reason\":\" Positions filled \"}"
                .formatted(r1, r2, r3, r4)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.approved").value(3))
        .andExpect(jsonPath("$.data.skipped.length()").value(0))
        .andExpect(jsonPath("$.data.failed.length()").value(1))
        .andExpect(jsonPath("$.data.failed[0].id").value(r4))
        .andExpect(jsonPath("$.data.failed[0].message")
            .value(ErrorCode.HR_REQUEST_ALREADY_HANDLED.getMessage()));

    for (String id : List.of(r1, r2, r3)) {
      WorkerJoinRequest req = template.findById(id, WorkerJoinRequest.class);
      assertEquals(WorkerJoinStatus.REJECTED, req.getStatus());
      assertEquals("Positions filled", req.getRejectionReason());
      assertEquals(shop.owner().userId(), req.getDecidedByUserId());
    }
    assertEquals(WorkerJoinStatus.APPROVED, template.findById(r4, WorkerJoinRequest.class).getStatus());
    for (Session worker : List.of(one, two, three)) {
      String state = me(worker, "NO_SHOP");
      assertEquals("REJECTED", JsonPath.read(state, "$.data.lastOutcome.status"));
      assertEquals("Positions filled", JsonPath.read(state, "$.data.lastOutcome.reason"));
      assertEquals(1, notifications(worker, HrNotificationType.JOIN_REQUEST_REJECTED).size());
    }
    me(done, "ACTIVE");
    assertEquals(3, audit(shop, HrAuditAction.JOIN_REQUEST_REJECTED).size(),
        "one history entry per request (R4.11)");
    mvc.perform(asUser(get(INBOX), shop.owner()))
        .andExpect(jsonPath("$.data.pendingCount").value(0))
        .andExpect(jsonPath("$.data.items.length()").value(0));
    mvc.perform(asUser(get(INBOX).param("status", "REJECTED"), shop.owner()))
        .andExpect(jsonPath("$.data.items.length()").value(3));

    // the one reason is checked once, before anything is decided
    Session four = signupWorker("Mohan Das", "9876500155");
    String r5 = join(four, shop);
    mvc.perform(asUser(post(INBOX + "/bulk/reject"), shop.owner())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"ids\":[\"%s\"],\"reason\":\"%s\"}".formatted(r5, "z".repeat(201))))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("The reason must be at most 200 characters"));
    assertEquals(WorkerJoinStatus.PENDING, template.findById(r5, WorkerJoinRequest.class).getStatus());
  }

  // ---- who may not use the inbox (R4.1, R2.1) ----------------------------------------------------

  @Test
  void aCashierWithoutAddEditEmployeesAndAWorkerAreRefusedOnEveryInboxCall() throws Exception {
    ShopFixture shop = newShop("Harish Gupta", "Gupta Stores");
    Session cashier = staffMember(shop, "Counter Person", UserRole.CASHIER);
    Session worker = activeWorker(shop, "Neha Kapoor", "9876500161");
    Session applicant = signupWorker("Priya Sharma", "9876500162");
    String requestId = join(applicant, shop);
    String employeeId = linkedEmployee(worker).getId();

    // the cashier is a member of the shop but holds no HR permission by default (R1.4 grid)
    for (Session denied : List.of(cashier, worker)) {
      expectDenied(asUser(get(INBOX), denied), ErrorCode.ACCESS_DENIED);
      expectDenied(asUser(get(INBOX + "/" + requestId), denied), ErrorCode.ACCESS_DENIED);
      expectDenied(asUser(post(INBOX + "/" + requestId + "/approve"), denied)
          .contentType(MediaType.APPLICATION_JSON).content("{\"mode\":\"CREATE\"}"),
          ErrorCode.ACCESS_DENIED);
      expectDenied(asUser(post(INBOX + "/" + requestId + "/reject"), denied)
          .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"no\"}"),
          ErrorCode.ACCESS_DENIED);
      expectDenied(asUser(post(INBOX + "/bulk/approve"), denied)
          .contentType(MediaType.APPLICATION_JSON).content(ids(requestId)),
          ErrorCode.ACCESS_DENIED);
      expectDenied(asUser(post(INBOX + "/bulk/reject"), denied)
          .contentType(MediaType.APPLICATION_JSON).content(ids(requestId)),
          ErrorCode.ACCESS_DENIED);
      expectDenied(asUser(post(HR + "/employees/" + employeeId + "/request-profile"), denied),
          ErrorCode.ACCESS_DENIED);
    }
    // nothing was decided or stamped
    WorkerJoinRequest untouched = template.findById(requestId, WorkerJoinRequest.class);
    assertEquals(WorkerJoinStatus.PENDING, untouched.getStatus());
    assertNull(untouched.getDecidedAt());
    assertNull(template.findById(employeeId, Employee.class).getProfileRequestedAt());
    me(applicant, "PENDING");

    // the owner, for contrast
    mvc.perform(asUser(get(INBOX), shop.owner()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.pendingCount").value(1));
  }

  @Test
  void aCashierGrantedAddEditEmployeesUsesTheInboxAndSeesTheBellRowOnlyThen() throws Exception {
    ShopFixture shop = newShop("Harish Gupta", "Gupta Hardware");
    Session cashier = staffMember(shop, "Counter Person", UserRole.CASHIER);
    Session applicant = signupWorker("Priya Sharma", "9876500163");
    String requestId = join(applicant, shop);

    // before the grant: no tab, and no JOIN_REQUEST_RECEIVED row in the bell (R4.1, R10.5)
    expectDenied(asUser(get(INBOX), cashier), ErrorCode.ACCESS_DENIED);
    mvc.perform(asUser(get(HR + "/me/notifications"), cashier))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.items[?(@.type == 'JOIN_REQUEST_RECEIVED')]").isEmpty());
    // the owner's bell has it, with the request id and the worker's name only (R4.13, R10.3)
    String received = "$.data.items[?(@.type == 'JOIN_REQUEST_RECEIVED')]";
    MvcResult ownerBell = mvc.perform(asUser(get(HR + "/me/notifications"), shop.owner()))
        .andExpect(status().isOk())
        .andExpect(jsonPath(received, org.hamcrest.Matchers.hasSize(1)))
        .andExpect(jsonPath(received + ".joinRequestId").value(requestId))
        .andExpect(jsonPath(received + ".text").value("Priya Sharma asked to join"))
        .andReturn();
    List<Object> leaveIds = JsonPath.read(json(ownerBell), received + ".leaveRequestId");
    assertTrue(leaveIds.isEmpty() || leaveIds.get(0) == null, "no leave id on a join row");
    assertFalse(json(ownerBell).contains("9876500163"));
    assertFalse(json(ownerBell).contains(applicant.email()));

    // the owner ticks "add and edit employees" for cashiers
    mvc.perform(asUser(put(HR + "/role-permissions"), shop.owner())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"roles\":{\"CASHIER\":[\"ADD_EDIT_EMPLOYEES\"]}}"))
        .andExpect(status().isOk());

    // the gate is the permission, not the role: tab, detail, bell row, and a decision
    mvc.perform(asUser(get(INBOX), cashier))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.pendingCount").value(1))
        .andExpect(jsonPath("$.data.items[0].id").value(requestId));
    mvc.perform(asUser(get(INBOX + "/" + requestId), cashier))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.name").value("Priya Sharma"));
    mvc.perform(asUser(get(HR + "/me/notifications"), cashier))
        .andExpect(status().isOk())
        .andExpect(jsonPath(received, org.hamcrest.Matchers.hasSize(1)))
        .andExpect(jsonPath(received + ".joinRequestId").value(requestId));
    mvc.perform(asUser(post(INBOX + "/" + requestId + "/approve"), cashier)
            .contentType(MediaType.APPLICATION_JSON).content("{\"mode\":\"CREATE\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.outcome").value("APPROVED_CREATED"));
    assertEquals(cashier.userId(),
        template.findById(requestId, WorkerJoinRequest.class).getDecidedByUserId());
    assertEquals(cashier.userId(),
        audit(shop, HrAuditAction.JOIN_REQUEST_APPROVED).get(0).getActorUserId());
    me(applicant, "ACTIVE");
    // and "Request profile details" opens with the same permission (R8.7)
    String employeeId = linkedEmployee(applicant).getId();
    mvc.perform(asUser(post(HR + "/employees/" + employeeId + "/request-profile"), cashier))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.profileRequestedAt").isString());
  }

  // ---- Request profile details (R8.7, R10.2) -----------------------------------------------------

  @Test
  void requestProfileStampsTheEmployeeAndTellsTheWorker() throws Exception {
    ShopFixture shop = newShop("Pooja Reddy", "Reddy Stores");
    Session worker = activeWorker(shop, "Arjun Rao", "9876500171");
    Employee mine = linkedEmployee(worker);
    assertNull(mine.getProfileRequestedAt());
    mvc.perform(asUser(get(WORKER + "/profile"), worker))
        .andExpect(jsonPath("$.data.profileRequested").value(false));

    mvc.perform(asUser(post(HR + "/employees/" + mine.getId() + "/request-profile"), shop.owner()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.id").value(mine.getId()))
        .andExpect(jsonPath("$.data.profileRequestedAt").isString());
    Instant first = template.findById(mine.getId(), Employee.class).getProfileRequestedAt();
    assertNotNull(first);
    assertTrue(Duration.between(first, Instant.now()).abs().toMinutes() < 5);

    // the worker: a notification, the banner count, and the profile flag
    List<HrNotification> told = notifications(worker, HrNotificationType.PROFILE_REQUESTED);
    assertEquals(1, told.size());
    assertEquals("Reddy Stores asked for your profile details", told.get(0).getText());
    mvc.perform(asUser(get(WORKER + "/notifications"), worker))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.unread").value(2))
        .andExpect(jsonPath("$.data.items[0].type").value("PROFILE_REQUESTED"))
        .andExpect(jsonPath("$.data.items[1].type").value("JOIN_REQUEST_APPROVED"));
    String home = me(worker, "ACTIVE");
    assertEquals(2, ((Number) JsonPath.read(home, "$.data.notifications.unread")).intValue());
    assertEquals("PROFILE_REQUESTED", JsonPath.read(home, "$.data.notifications.top[0].type"));
    mvc.perform(asUser(get(WORKER + "/profile"), worker))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.profileRequested").value(true));
    // one history row on the employee (R8.7)
    List<HrAuditEvent> audit = audit(shop, HrAuditAction.PROFILE_REQUESTED);
    assertEquals(1, audit.size());
    assertEquals(shop.owner().userId(), audit.get(0).getActorUserId());
    assertEquals(mine.getId(), audit.get(0).getEmployeeId());

    // asking again moves the stamp forward and tells the worker again
    Thread.sleep(5);
    mvc.perform(asUser(post(HR + "/employees/" + mine.getId() + "/request-profile"), shop.owner()))
        .andExpect(status().isOk());
    Instant second = template.findById(mine.getId(), Employee.class).getProfileRequestedAt();
    assertTrue(second.isAfter(first), second + " after " + first);
    assertEquals(2, notifications(worker, HrNotificationType.PROFILE_REQUESTED).size());

    // an employee without a login: nobody to ask
    Employee noLogin = employee(shop, "EMP-401", "No Login", null);
    mvc.perform(asUser(post(HR + "/employees/" + noLogin.getId() + "/request-profile"),
            shop.owner()))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("This employee has no linked login to ask"));
    assertNull(template.findById(noLogin.getId(), Employee.class).getProfileRequestedAt());
  }

  // ---- withdraw (R3.6, R4.10) --------------------------------------------------------------------

  @Test
  void withdrawTakesTheRequestBackAndLeavesTheApproverNothingToDecide() throws Exception {
    ShopFixture shop = newShop("Kiran Bhat", "Bhat Stores");
    Session priya = signupWorker("Priya Sharma", "9876500181");
    Session stranger = signupWorker("Some Stranger", "9876500182");
    String requestId = join(priya, shop);
    mvc.perform(asUser(get(INBOX), shop.owner()))
        .andExpect(jsonPath("$.data.pendingCount").value(1));

    // somebody else's request: not found, not withdrawn
    mvc.perform(asUser(post(WORKER + "/join-requests/" + requestId + "/withdraw"), stranger))
        .andExpect(status().isNotFound());
    assertEquals(WorkerJoinStatus.PENDING,
        template.findById(requestId, WorkerJoinRequest.class).getStatus());

    mvc.perform(asUser(post(WORKER + "/join-requests/" + requestId + "/withdraw"), priya))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.id").value(requestId))
        .andExpect(jsonPath("$.data.status").value("WITHDRAWN"))
        .andExpect(jsonPath("$.data.shopName").value("Bhat Stores"));
    WorkerJoinRequest withdrawn = template.findById(requestId, WorkerJoinRequest.class);
    assertEquals(WorkerJoinStatus.WITHDRAWN, withdrawn.getStatus());
    assertEquals(JoinOutcome.WITHDRAWN, withdrawn.getOutcome());
    assertEquals(priya.userId(), withdrawn.getDecidedByUserId());
    assertNotNull(withdrawn.getDecidedAt());
    assertRetention(withdrawn);

    String state = me(priya, "NO_SHOP");
    assertEquals("WITHDRAWN", JsonPath.read(state, "$.data.lastOutcome.status"));
    assertEquals(requestId, JsonPath.read(state, "$.data.lastOutcome.id"));
    assertNull(JsonPath.read(state, "$.data.lastOutcome.reason"));
    assertEquals(0, ((Number) JsonPath.read(state, "$.data.notifications.unread")).intValue(),
        "withdrawing tells nobody");

    // gone from the tab; the approver's buttons meet "already handled" (R4.10)
    mvc.perform(asUser(get(INBOX), shop.owner()))
        .andExpect(jsonPath("$.data.pendingCount").value(0))
        .andExpect(jsonPath("$.data.items.length()").value(0));
    mvc.perform(asUser(get(INBOX).param("status", "REJECTED"), shop.owner()))
        .andExpect(jsonPath("$.data.items.length()").value(0));
    expectRefusal(approve(shop, requestId, "{\"mode\":\"CREATE\"}"),
        ErrorCode.HR_REQUEST_ALREADY_HANDLED);
    expectRefusal(reject(shop, requestId, "{}"), ErrorCode.HR_REQUEST_ALREADY_HANDLED);
    expectRefusal(asUser(post(WORKER + "/join-requests/" + requestId + "/withdraw"), priya),
        ErrorCode.HR_REQUEST_ALREADY_HANDLED);
    assertEquals(0, audit(shop, HrAuditAction.JOIN_REQUEST_APPROVED).size());
    assertEquals(0, employees(shop));

    // free to ask again, and that request can be approved
    String again = join(priya, shop);
    assertNotEquals(requestId, again);
    me(priya, "PENDING");
    approveCreate(shop, again);
    me(priya, "ACTIVE");
  }

  // ---- one open request per account, even in a race (R3.5, R13.5; design DD2) -------------------

  @Test
  void aSecondOpenRequestIsRefusedByThePartialUniqueIndexEvenWhenTwoArriveAtOnce()
      throws Exception {
    ShopFixture shop = newShop("Nitin Joshi", "Joshi Stores");
    ShopFixture otherShop = newShop("Rekha Pillai", "Pillai Stores");
    Session priya = signupWorker("Priya Sharma", "9876500191");

    // the plain case: a second request while one waits
    String first = join(priya, shop);
    expectRefusal(asUser(post(WORKER + "/join-requests"), priya)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"shopId\":\"" + otherShop.shopId() + "\"}"),
        ErrorCode.HR_WORKER_REQUEST_PENDING);
    assertEquals(1, openRequests(priya.userId()));
    mvc.perform(asUser(post(WORKER + "/join-requests/" + first + "/withdraw"), priya))
        .andExpect(status().isOk());
    assertEquals(0, openRequests(priya.userId()));

    // the race: two requests, two shops, released together
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      CountDownLatch go = new CountDownLatch(1);
      List<Future<MvcResult>> results = new ArrayList<>();
      for (ShopFixture target : List.of(shop, otherShop)) {
        results.add(pool.submit(() -> {
          go.await(10, TimeUnit.SECONDS);
          return mvc.perform(asUser(post(WORKER + "/join-requests"), priya)
              .contentType(MediaType.APPLICATION_JSON)
              .content("{\"shopId\":\"" + target.shopId() + "\"}")).andReturn();
        }));
      }
      go.countDown();
      int accepted = 0;
      int refused = 0;
      for (Future<MvcResult> f : results) {
        MvcResult r = f.get(30, TimeUnit.SECONDS);
        if (r.getResponse().getStatus() == 200) {
          accepted++;
        } else {
          refused++;
          assertEquals(ErrorCode.HR_WORKER_REQUEST_PENDING.getHttpStatus().value(),
              r.getResponse().getStatus(), json(r));
          assertEquals(ErrorCode.HR_WORKER_REQUEST_PENDING.getMessage(),
              JsonPath.read(json(r), "$.error"));
        }
      }
      assertEquals(1, accepted, "exactly one request is filed");
      assertEquals(1, refused);
    } finally {
      pool.shutdownNow();
    }
    assertEquals(1, openRequests(priya.userId()));
    me(priya, "PENDING");

    // the index itself, without the service's pre-check in the way: two inserts at once
    Session ravi = signupWorker("Ravi Kumar", "9876500192");
    ExecutorService inserters = Executors.newFixedThreadPool(2);
    try {
      CountDownLatch go = new CountDownLatch(1);
      List<Future<Boolean>> inserted = new ArrayList<>();
      for (ShopFixture target : List.of(shop, otherShop)) {
        inserted.add(inserters.submit(() -> {
          go.await(10, TimeUnit.SECONDS);
          try {
            template.insert(pendingRequest(ravi, target));
            return true;
          } catch (DuplicateKeyException e) {
            return false;
          }
        }));
      }
      go.countDown();
      int ok = 0;
      for (Future<Boolean> f : inserted) {
        if (f.get(30, TimeUnit.SECONDS)) {
          ok++;
        }
      }
      assertEquals(1, ok, "the partial unique index lets exactly one open request through");
    } finally {
      inserters.shutdownNow();
    }
    assertEquals(1, openRequests(ravi.userId()));
    // the index is partial: once that request is decided, a new one may be filed
    template.updateFirst(Query.query(Criteria.where("userId").is(ravi.userId())),
        new Update().set("status", WorkerJoinStatus.REJECTED).set("decidedAt", Instant.now()),
        WorkerJoinRequest.class);
    template.insert(pendingRequest(ravi, shop));
    assertEquals(1, openRequests(ravi.userId()));
    assertEquals(2, template.count(Query.query(Criteria.where("userId").is(ravi.userId())),
        WorkerJoinRequest.class));
  }

  // ---- the exit job's two sweeps (R5.2, R5.3; design DD12, DD14) ---------------------------------

  @Test
  void theExitJobEndsDueMembershipsUnpairsDuePhonesAndResetsStuckApprovals() throws Exception {
    ShopFixture shop = newShop("Sunil Kumar", "Kumar Stores");

    // (a) Leave this shop: everything ends within the save; the job then has nothing to do
    Session leaver = activeWorker(shop, "Deepa Singh", "9876500201");
    Employee leaving = linkedEmployee(leaver);
    Phone leaverPhone = Phone.create();
    String leaverPairing = pairWithLogin(leaver, createCode(shop, leaving.getId()), leaverPhone);
    mvc.perform(asUser(post(WORKER + "/leave"), leaver))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.state").value("NO_SHOP"));
    Employee left = template.findById(leaving.getId(), Employee.class);
    assertEquals(EmployeeStatus.EXITED, left.getStatus());
    assertEquals(today(), left.getExitDate());
    assertNotNull(left.getMembershipEndedAt());
    assertNotEquals(PairingStatus.ACTIVE,
        template.findById(leaverPairing, DevicePairing.class).getStatus(),
        "an exit today unpairs the phone at once (HR R3.4)");
    assertNull(template.findById(leaver.userId(), UserAccount.class).getShopId());
    Instant now = Instant.now();
    assertEquals(0, exitJob.endDue(now));
    assertEquals(0, pairingExitJob.unpairDue(now));
    assertNotNull(template.findById(leaving.getId(), Employee.class).getMembershipEndedAt());

    // (b) the manager sets an exit for tomorrow: the membership and the phone last until then
    Session stayer = activeWorker(shop, "Arjun Rao", "9876500202");
    Employee staying = linkedEmployee(stayer);
    Phone stayerPhone = Phone.create();
    String stayerPairing = pairWithLogin(stayer, createCode(shop, staying.getId()), stayerPhone);
    LocalDate tomorrow = HrDateUtils.today().plusDays(1);
    mvc.perform(asUser(post(HR + "/employees/" + staying.getId() + "/exit"), shop.owner())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"exitDate\":\"" + HrDateUtils.format(tomorrow) + "\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.status").value("EXITED"));
    Employee scheduled = template.findById(staying.getId(), Employee.class);
    assertEquals(EmployeeStatus.EXITED, scheduled.getStatus());
    assertNull(scheduled.getMembershipEndedAt(), "not before the exit date (R5.2)");
    assertEquals(shop.shopId(), template.findById(stayer.userId(), UserAccount.class).getShopId());
    assertTrue(memberships(stayer).get(0).isActive());
    DevicePairing pairing = template.findById(stayerPairing, DevicePairing.class);
    assertEquals(PairingStatus.ACTIVE, pairing.getStatus());
    assertEquals(tomorrow.atStartOfDay(HrDateUtils.ZONE).toInstant(),
        pairing.getScheduledUnpairAt());
    // today's tick: nothing is due yet
    assertEquals(0, exitJob.endDue(Instant.now()));
    assertEquals(0, pairingExitJob.unpairDue(Instant.now()));
    assertNull(template.findById(staying.getId(), Employee.class).getMembershipEndedAt());
    // and the account is still this shop's: a new request is refused (R3.7)
    expectRefusal(asUser(post(WORKER + "/join-requests"), stayer)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"shopId\":\"" + shop.shopId() + "\"}"),
        ErrorCode.HR_WORKER_HAS_SHOP);

    // the first tick after 00:00 Asia/Kolkata of the exit date
    Instant afterMidnight = tomorrow.atStartOfDay(HrDateUtils.ZONE).toInstant()
        .plus(1, ChronoUnit.MINUTES);
    assertEquals(1, exitJob.endDue(afterMidnight));
    assertEquals(1, pairingExitJob.unpairDue(afterMidnight));
    Employee ended = template.findById(staying.getId(), Employee.class);
    assertNotNull(ended.getMembershipEndedAt());
    assertEquals(stayer.userId(), ended.getUserId(), "the link stays for payslips (R5.2)");
    assertNull(template.findById(stayer.userId(), UserAccount.class).getShopId());
    List<UserShopMembership> memberships = memberships(stayer);
    assertEquals(1, memberships.size());
    assertFalse(memberships.get(0).isActive());
    assertNotNull(memberships.get(0).getEndedAt());
    assertNotEquals(PairingStatus.ACTIVE,
        template.findById(stayerPairing, DevicePairing.class).getStatus());
    // idempotent: the next tick finds nothing
    assertEquals(0, exitJob.endDue(afterMidnight.plus(1, ChronoUnit.MINUTES)));
    assertEquals(0, pairingExitJob.unpairDue(afterMidnight.plus(1, ChronoUnit.MINUTES)));
    me(stayer, "NO_SHOP");
    join(stayer, shop);
    me(stayer, "PENDING");

    // (c) a request stuck in APPROVING is given back after five minutes
    Session stuck = signupWorker("Priya Sharma", "9876500203");
    String stuckId = join(stuck, shop);
    Session fresh = signupWorker("Ravi Kumar", "9876500204");
    String freshId = join(fresh, shop);
    Instant sixMinutesAgo = Instant.now().minus(6, ChronoUnit.MINUTES);
    template.updateFirst(Query.query(Criteria.where("_id").is(stuckId)),
        new Update().set("status", WorkerJoinStatus.APPROVING).set("updatedAt", sixMinutesAgo),
        WorkerJoinRequest.class);
    template.updateFirst(Query.query(Criteria.where("_id").is(freshId)),
        new Update().set("status", WorkerJoinStatus.APPROVING).set("updatedAt", Instant.now()),
        WorkerJoinRequest.class);
    // while locked, nobody else can decide it, and the worker still waits
    expectRefusal(approve(shop, stuckId, "{\"mode\":\"CREATE\"}"),
        ErrorCode.HR_REQUEST_ALREADY_HANDLED);
    expectRefusal(reject(shop, stuckId, "{}"), ErrorCode.HR_REQUEST_ALREADY_HANDLED);
    me(stuck, "PENDING");
    assertEquals(1, exitJob.resetStuckApprovals(Instant.now()),
        "the old lock is reset, the fresh one is left alone");
    assertEquals(WorkerJoinStatus.PENDING,
        template.findById(stuckId, WorkerJoinRequest.class).getStatus());
    assertEquals(WorkerJoinStatus.APPROVING,
        template.findById(freshId, WorkerJoinRequest.class).getStatus());
    assertEquals(0, exitJob.resetStuckApprovals(Instant.now()));
    approveCreate(shop, stuckId);
    me(stuck, "ACTIVE");
    // put the fresh lock back so the row does not sit APPROVING for other tests in this JVM
    template.updateFirst(Query.query(Criteria.where("_id").is(freshId)),
        new Update().set("status", WorkerJoinStatus.PENDING), WorkerJoinRequest.class);
  }

  // ---- helpers -----------------------------------------------------------------------------------

  private MockHttpServletRequestBuilder approve(ShopFixture shop, String requestId, String body) {
    return asUser(post(INBOX + "/" + requestId + "/approve"), shop.owner())
        .contentType(MediaType.APPLICATION_JSON).content(body);
  }

  private MockHttpServletRequestBuilder reject(ShopFixture shop, String requestId, String body) {
    return asUser(post(INBOX + "/" + requestId + "/reject"), shop.owner())
        .contentType(MediaType.APPLICATION_JSON).content(body);
  }

  private static String linkBody(String employeeId) {
    return "{\"mode\":\"LINK\",\"employeeId\":\"" + employeeId + "\"}";
  }

  private static String ids(String... ids) {
    StringBuilder b = new StringBuilder("{\"ids\":[");
    for (int i = 0; i < ids.length; i++) {
      b.append(i > 0 ? ",\"" : "\"").append(ids[i]).append('"');
    }
    return b.append("]}").toString();
  }

  private static Map<String, String> failedById(String json) {
    Map<String, String> byId = new java.util.HashMap<>();
    for (Map<String, String> f : JsonPath.<List<Map<String, String>>>read(json, "$.data.failed")) {
      byId.put(f.get("id"), f.get("message"));
    }
    return byId;
  }

  /** A refused approval left the request Pending and the account, the shop and its roll alone. */
  private void assertNothingChanged(ShopFixture shop, Session worker, String requestId,
      long employeesBefore) {
    WorkerJoinRequest request = template.findById(requestId, WorkerJoinRequest.class);
    assertEquals(WorkerJoinStatus.PENDING, request.getStatus(), "the request stays Pending");
    assertNull(request.getDecidedAt());
    assertNull(request.getEmployeeId());
    assertNull(template.findById(worker.userId(), UserAccount.class).getShopId(),
        "the claimed shop was rolled back");
    assertEquals(0, memberships(worker).size());
    assertEquals(employeesBefore, employees(shop));
    assertEquals(0, template.count(Query.query(Criteria.where("userId").is(worker.userId())),
        Employee.class));
    assertEquals(0, audit(shop, HrAuditAction.JOIN_REQUEST_APPROVED).size());
  }

  /** A decided request is kept 90 days (R4.12, R11.3). */
  private static void assertRetention(WorkerJoinRequest decided) {
    assertNotNull(decided.getExpiresAt());
    long days = Duration.between(decided.getDecidedAt(), decided.getExpiresAt()).toDays();
    assertEquals(90, days, "expiresAt = decidedAt + 90 d");
  }

  private WorkerJoinRequest pendingRequest(Session worker, ShopFixture shop) {
    WorkerJoinRequest r = new WorkerJoinRequest();
    r.setShopId(shop.shopId());
    r.setUserId(worker.userId());
    r.setStatus(WorkerJoinStatus.PENDING);
    r.setSnapshot(new WorkerJoinRequest.Snapshot("Ravi Kumar", "9876500192", worker.email(),
        false));
    r.setRequestedAt(Instant.now());
    r.setUpdatedAt(Instant.now());
    return r;
  }

  private long openRequests(String userId) {
    return template.count(Query.query(Criteria.where("userId").is(userId)
        .and("status").in(WorkerJoinStatus.PENDING, WorkerJoinStatus.APPROVING)),
        WorkerJoinRequest.class);
  }

  private long employees(ShopFixture shop) {
    return template.count(Query.query(Criteria.where("shopId").is(shop.shopId())), Employee.class);
  }

  private List<UserShopMembership> memberships(Session s) {
    return template.find(Query.query(Criteria.where("userId").is(s.userId())),
        UserShopMembership.class);
  }

  private Employee linkedEmployee(Session s) {
    Employee e = template.findOne(Query.query(Criteria.where("userId").is(s.userId())
        .and("status").is(EmployeeStatus.ACTIVE)), Employee.class);
    assertNotNull(e, "a linked ACTIVE employee for " + s.email());
    return e;
  }

  private List<HrAuditEvent> audit(ShopFixture shop, HrAuditAction action) {
    return template.find(Query.query(Criteria.where("shopId").is(shop.shopId())
        .and("action").is(action)), HrAuditEvent.class);
  }

  private List<HrNotification> notifications(Session worker, HrNotificationType type) {
    return template.find(Query.query(Criteria.where("recipientUserId").is(worker.userId())
        .and("type").is(type)), HrNotification.class);
  }

  private static String today() {
    return HrDateUtils.format(HrDateUtils.today());
  }
}
