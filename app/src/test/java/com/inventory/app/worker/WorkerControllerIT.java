package com.inventory.app.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.inventory.common.constants.ErrorCode;
import com.inventory.hr.domain.model.DevicePairing;
import com.inventory.hr.domain.model.Employee;
import com.inventory.hr.domain.model.HrAuditEvent;
import com.inventory.hr.domain.model.PairingCode;
import com.inventory.hr.domain.model.Punch;
import com.inventory.hr.domain.model.WorkerJoinRequest;
import com.inventory.hr.domain.model.enums.EmployeeStatus;
import com.inventory.hr.domain.model.enums.HrAuditAction;
import com.inventory.hr.domain.model.enums.PairingCodeStatus;
import com.inventory.hr.domain.model.enums.PairingStatus;
import com.inventory.hr.domain.model.enums.WorkerJoinStatus;
import com.inventory.hr.utils.HrDateUtils;
import com.inventory.product.domain.model.Shop;
import com.inventory.user.domain.model.UserAccount;
import com.inventory.user.domain.model.UserRole;
import com.inventory.user.domain.model.UserShopMembership;
import com.jayway.jsonpath.JsonPath;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Stockkart Team end to end against the real application and a real MongoDB (worker-app
 * task 6.9; requirements 1.5, 2.1, 3.2–3.4, 5.3, 5.6, 6.1, 7.3, 7.5, 7.6, 7.9, 8.4, 9.1, 9.10):
 * the worker signs up, finds the shop, asks to join, the owner approves, the worker's Home comes
 * up, the owner makes a pairing code, the signed-in app pairs the phone, the phone punches over
 * the device envelope alone, and Home and History show the punch. Around that: the wrong worker
 * cannot spend a code, a staff login without an employee is told so, the unsigned handshake of the
 * old app is no route at all (D11), a punch is the same whether the session is live, expired or
 * logged out, Leave this shop frees the account, and an expired trial does not lock the worker out
 * of the app. See {@link WorkerAppItSupport} for how
 * the application is booted and what it needs.
 */
@EnabledIfSystemProperty(named = WorkerAppItSupport.MONGO_PROPERTY, matches = ".+")
class WorkerControllerIT extends WorkerAppItSupport {

  // ---- the release-gate flow ----------------------------------------------------------------------

  @Test
  void signUpFindShopJoinApprovePairAndPunch() throws Exception {
    ShopFixture shop = newShop("Ramesh Kumar", "Sri Ganesh Stores");

    // 1. sign-up from the Stockkart Team app: WORKER, no shop, no membership, no employee (R1.5)
    Session priya = signupWorker("Priya Sharma", "+91 98765 43210");
    UserAccount account = template.findById(priya.userId(), UserAccount.class);
    assertEquals(UserRole.WORKER, account.getRole());
    assertNull(account.getShopId());
    assertEquals("9876543210", account.getPhone(), "phone normalised to 10 digits (R1.2)");
    assertEquals(0, memberships(priya).size());
    assertEquals(0, template.find(Query.query(Criteria.where("userId").is(priya.userId())),
        Employee.class).size());

    // 2. the app's first state read: the Join screen (R6.1)
    String noShop = me(priya, "NO_SHOP");
    assertEquals(List.of(), JsonPath.read(noShop, "$.data.modules"));
    assertEquals("WORKER", JsonPath.read(noShop, "$.data.account.role"));
    mvc.perform(asUser(get(WORKER + "/me"), priya))
        .andExpect(jsonPath("$.data.request").doesNotExist())
        .andExpect(jsonPath("$.data.shop").doesNotExist())
        .andExpect(jsonPath("$.data.employee").doesNotExist())
        .andExpect(jsonPath("$.data.pairing").doesNotExist())
        .andExpect(jsonPath("$.data.notifications.unread").value(0));

    // 3. Find shop: the owner's e-mail answers the shop and the owner's first name (R3.2)
    mvc.perform(asUser(get(WORKER + "/shops").param("ownerEmail", shop.owner().email()), priya))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.shops.length()").value(1))
        .andExpect(jsonPath("$.data.shops[0].shopId").value(shop.shopId()))
        .andExpect(jsonPath("$.data.shops[0].shopName").value("Sri Ganesh Stores"))
        .andExpect(jsonPath("$.data.shops[0].ownerFirstName").value("Ramesh"));

    // 4. Yes, this is my shop → Waiting (R3.4, R6.1)
    String requestId = join(priya, shop);
    String pending = me(priya, "PENDING");
    assertEquals(requestId, JsonPath.read(pending, "$.data.request.id"));
    assertEquals("Sri Ganesh Stores", JsonPath.read(pending, "$.data.request.shopName"));
    assertEquals("PENDING", JsonPath.read(pending, "$.data.request.status"));
    assertEquals(List.of(), JsonPath.read(pending, "$.data.modules"));
    WorkerJoinRequest stored = template.findById(requestId, WorkerJoinRequest.class);
    assertEquals("Priya Sharma", stored.getSnapshot().getName());
    assertEquals("9876543210", stored.getSnapshot().getPhone());
    assertEquals(priya.email(), stored.getSnapshot().getEmail());
    assertNotNull(stored.getRequestedAt());

    // 5. the owner sees it in the inbox and approves with CREATE (R4.5)
    mvc.perform(asUser(get(INBOX).param("status", "PENDING"), shop.owner()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.pendingCount").value(1))
        .andExpect(jsonPath("$.data.items[0].id").value(requestId))
        .andExpect(jsonPath("$.data.items[0].kind").doesNotExist())
        .andExpect(jsonPath("$.data.items[0].name").value("Priya Sharma"))
        .andExpect(jsonPath("$.data.items[0].hasPossibleMatch").value(false));
    String employeeId = approveCreate(shop, requestId);

    Employee created = template.findById(employeeId, Employee.class);
    assertEquals(shop.shopId(), created.getShopId());
    assertEquals(EmployeeStatus.ACTIVE, created.getStatus());
    assertEquals(priya.userId(), created.getUserId());
    assertEquals("Priya Sharma", created.getName());
    assertEquals("9876543210", created.getPhone());
    assertEquals(priya.email(), created.getEmail());
    assertEquals(today(), created.getJoinDate());
    assertEquals(shop.owner().userId(), created.getCreatedByUserId());
    assertTrue(created.getSalaryRevisions() == null || created.getSalaryRevisions().isEmpty());
    List<UserShopMembership> memberships = memberships(priya);
    assertEquals(1, memberships.size());
    assertEquals(UserRole.WORKER, memberships.get(0).getRole());
    assertEquals(shop.shopId(), memberships.get(0).getShopId());
    assertTrue(memberships.get(0).isActive());
    assertEquals(shop.shopId(), template.findById(priya.userId(), UserAccount.class).getShopId(),
        "the account's active shop is the DD1 lock");
    assertEquals(WorkerJoinStatus.APPROVED,
        template.findById(requestId, WorkerJoinRequest.class).getStatus());

    // 6. Home: modules, the employee, Not paired, Not checked in (R6.1, R6.4, R7.1)
    String active = me(priya, "ACTIVE");
    assertEquals(List.of("ATTENDANCE", "HISTORY", "PROFILE"),
        JsonPath.read(active, "$.data.modules"));
    assertEquals(shop.shopId(), JsonPath.read(active, "$.data.shop.shopId"));
    assertEquals("Sri Ganesh Stores", JsonPath.read(active, "$.data.shop.shopName"));
    assertEquals(employeeId, JsonPath.read(active, "$.data.employee.employeeId"));
    assertEquals(created.getCode(), JsonPath.read(active, "$.data.employee.code"));
    assertEquals("NOT_PAIRED", JsonPath.read(active, "$.data.pairing.state"));
    assertEquals(today(), JsonPath.read(active, "$.data.today.date"));
    mvc.perform(asUser(get(WORKER + "/me"), priya))
        .andExpect(jsonPath("$.data.today.in").doesNotExist())
        .andExpect(jsonPath("$.data.request").doesNotExist())
        .andExpect(jsonPath("$.data.notifications.unread").value(1))
        .andExpect(jsonPath("$.data.notifications.top[0].type").value("JOIN_REQUEST_APPROVED"));

    // 7. the owner makes a code; the signed-in app pairs with its Bearer token (R7.2, R7.3)
    String code = createCode(shop, employeeId);
    Phone phone = Phone.create();
    String pairingId = pairWithLogin(priya, code, phone);
    DevicePairing pairing = template.findById(pairingId, DevicePairing.class);
    assertEquals(PairingStatus.ACTIVE, pairing.getStatus());
    assertEquals(employeeId, pairing.getEmployeeId());
    assertEquals(spki(phone.punchKey()), pairing.getPunchPublicKey());
    assertEquals("PAIRED", JsonPath.read(me(priya, "ACTIVE"), "$.data.pairing.state"));
    assertEquals(pairingId, JsonPath.read(me(priya, "ACTIVE"), "$.data.pairing.pairingId"));

    // 8. the punch travels with the device envelope only: no Bearer anywhere (R7.6, R9.1, R13.1)
    MvcResult punched = mvc.perform(signedPunch(pairingId, shop.shopId(), employeeId, phone))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.outcome").value("STORED"))
        .andExpect(jsonPath("$.data.direction").value("IN"))
        .andExpect(jsonPath("$.data.atLocal").isString())
        .andExpect(jsonPath("$.data.todayIn").isString())
        .andReturn();
    String in = JsonPath.read(json(punched), "$.data.atLocal");

    // 9. Home shows today's check-in; History lists the punch (R6.5, R7.9)
    String afterPunch = me(priya, "ACTIVE");
    assertEquals(in, JsonPath.read(afterPunch, "$.data.today.in"));
    assertEquals("OUT", JsonPath.read(afterPunch, "$.data.today.nextDirection"));
    mvc.perform(asUser(get(WORKER + "/punches"), priya))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.length()").value(1))
        .andExpect(jsonPath("$.data[0].employeeId").value(employeeId))
        .andExpect(jsonPath("$.data[0].source").value("BIOMETRIC_APP"))
        .andExpect(jsonPath("$.data[0].direction").value("IN"))
        .andExpect(jsonPath("$.data[0].time").value(in))
        .andExpect(jsonPath("$.data[0].localDate").value(today()))
        .andExpect(jsonPath("$.data[0].counted").value(true));
    mvc.perform(asUser(get(WORKER + "/punches").param("from", today()).param("to", today()),
            priya))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.length()").value(1));

    // the profile carries the shop, the code and Paired, and nothing about pay (R8.1, R11.2)
    String profile = json(mvc.perform(asUser(get(WORKER + "/profile"), priya))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.name").value("Priya Sharma"))
        .andExpect(jsonPath("$.data.phone").value("9876543210"))
        .andExpect(jsonPath("$.data.shopName").value("Sri Ganesh Stores"))
        .andExpect(jsonPath("$.data.employeeCode").value(created.getCode()))
        .andExpect(jsonPath("$.data.pairingState").value("PAIRED"))
        .andReturn());
    for (String field : List.of("salary", "bankAccountNumber", "ifsc", "upiId", "paymentMethod")) {
      assertFalse(profile.contains(field), field);
    }
  }

  // ---- Find shop: every miss is one answer (R3.3, R11.4) -------------------------------------------

  @Test
  void everyKindOfLookupMissIsTheSameEmptyAnswer() throws Exception {
    Session worker = signupWorker("Kavya Nair", "9876500010");
    // an owner-role account that never registered a shop
    Session ownerWithoutShop = signupStaff("Owner Nobody", "OWNER");

    String unknown = lookup(worker, "nobody-" + UUID.randomUUID() + "@example.test");
    String nonOwner = lookup(worker, worker.email());
    String ownerNoShop = lookup(worker, ownerWithoutShop.email());

    String emptyAnswer = "{\"shops\":[]}";
    assertEquals(emptyAnswer, unknown);
    assertEquals(emptyAnswer, nonOwner);
    assertEquals(emptyAnswer, ownerNoShop);
    // the same answer in a different case and with spaces around it: an exact e-mail match
    assertEquals(emptyAnswer, lookup(worker, "  " + worker.email().toUpperCase() + "  "));

    // a blank e-mail is a request-shape error, not a lookup: nothing is searched
    mvc.perform(asUser(get(WORKER + "/shops").param("ownerEmail", "   "), worker))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.success").value(false));
    mvc.perform(asUser(get(WORKER + "/shops"), worker))
        .andExpect(status().isBadRequest());
  }

  private String lookup(Session worker, String ownerEmail) throws Exception {
    MvcResult r = mvc.perform(asUser(get(WORKER + "/shops").param("ownerEmail", ownerEmail),
            worker))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andReturn();
    // the data object only: the envelope's other fields do not depend on the e-mail
    return new com.fasterxml.jackson.databind.ObjectMapper()
        .writeValueAsString(JsonPath.read(json(r), "$.data"));
  }

  // ---- a code pairs only its own worker (R7.3) ----------------------------------------------------

  @Test
  void aCodeForAnotherWorkerIsRefusedAndStillWorksForItsOwner() throws Exception {
    ShopFixture shop = newShop("Meena Iyer", "Iyer Provisions");
    Session asha = activeWorker(shop, "Asha Verma", "9876500021");
    Session ravi = activeWorker(shop, "Ravi Kumar", "9876500022");
    String raviEmployee = linkedEmployee(ravi).getId();
    String ashaEmployee = linkedEmployee(asha).getId();

    String code = createCode(shop, raviEmployee);
    Phone ashaPhone = Phone.create();
    expectRefusal(asUser(post(WORKER + "/pairings/claim"), asha)
            .contentType(MediaType.APPLICATION_JSON).content(claimBody(code, ashaPhone)),
        ErrorCode.HR_PAIRING_WRONG_EMPLOYEE);

    // the refusal is on record against the code's employee, naming who tried
    List<HrAuditEvent> refused = template.find(Query.query(Criteria.where("shopId")
        .is(shop.shopId()).and("action").is(HrAuditAction.PAIRING_CODE_REFUSED)),
        HrAuditEvent.class);
    assertEquals(1, refused.size());
    assertEquals(raviEmployee, refused.get(0).getEmployeeId());
    assertEquals(asha.userId(), refused.get(0).getAfter().get("attemptedByUserId"));
    assertNull(template.findById(ashaEmployee, Employee.class).getExitDate());

    // the code is still Ravi's to spend
    Phone raviPhone = Phone.create();
    String pairingId = pairWithLogin(ravi, code, raviPhone);
    DevicePairing pairing = template.findById(pairingId, DevicePairing.class);
    assertEquals(raviEmployee, pairing.getEmployeeId());
    assertEquals(PairingStatus.ACTIVE, pairing.getStatus());
    assertEquals("NOT_PAIRED", JsonPath.read(me(asha, "ACTIVE"), "$.data.pairing.state"));
    assertEquals("PAIRED", JsonPath.read(me(ravi, "ACTIVE"), "$.data.pairing.state"));
  }

  // ---- a staff login without an employee (R5.6) -------------------------------------------------

  @Test
  void aStaffAccountWithoutAnEmployeeIsToldSoOnMeAndRefusedEverywhereElse() throws Exception {
    ShopFixture shop = newShop("Suresh Rao", "Rao Traders");
    Session owner = shop.owner();

    String state = me(owner, "STAFF_NO_EMPLOYEE");
    assertEquals(List.of(), JsonPath.read(state, "$.data.modules"));
    assertEquals("OWNER", JsonPath.read(state, "$.data.account.role"));

    expectRefusal(asUser(get(WORKER + "/profile"), owner), ErrorCode.HR_WORKER_ONLY);
    expectRefusal(asUser(get(WORKER + "/punches"), owner), ErrorCode.HR_WORKER_ONLY);
    expectRefusal(asUser(post(WORKER + "/join-requests"), owner)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"shopId\":\"" + shop.shopId() + "\"}"),
        ErrorCode.HR_WORKER_ONLY);
    expectRefusal(asUser(post(WORKER + "/leave"), owner), ErrorCode.HR_WORKER_ONLY);
    assertEquals(0, template.find(Query.query(Criteria.where("userId").is(owner.userId())),
        WorkerJoinRequest.class).size(), "the refused join request wrote nothing");

    // a staff account with a linked ACTIVE employee is a worker in the app (R5.6 default)
    Employee ownersOwn = employee(shop, "EMP-OWN", "Suresh Rao", owner.userId());
    String active = me(owner, "ACTIVE");
    assertEquals(ownersOwn.getId(), JsonPath.read(active, "$.data.employee.employeeId"));
    assertEquals(List.of("ATTENDANCE", "HISTORY", "PROFILE"),
        JsonPath.read(active, "$.data.modules"));
    mvc.perform(asUser(get(WORKER + "/profile"), owner))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.employeeCode").value("EMP-OWN"));
  }

  // ---- there is no handshake without a login (R7.5, D11, DD4) ------------------------------------

  @Test
  void theUnsignedHandshakeIsNoRouteAndTheCodeStaysTheWorkersToSpend() throws Exception {
    ShopFixture shop = newShop("Lakshmi Devi", "Devi General Store");
    Session priya = activeWorker(shop, "Priya Sharma", "9876500031");
    String employeeId = linkedEmployee(priya).getId();
    String code = createCode(shop, employeeId);
    Phone phone = Phone.create();

    // the old app's unsigned claim / complete / abandon: no handler. Without any credential the
    // Bearer chain answers as for any unknown /api route (401); with a login the route is simply
    // not there (404). Either way no code is read and nothing is counted on it.
    for (MockHttpServletRequestBuilder unsigned : List.of(
        post(DEVICE + "/pairings/claim").contentType(MediaType.APPLICATION_JSON)
            .content(claimBody(code, phone)),
        post(DEVICE + "/pairings/complete").contentType(MediaType.APPLICATION_JSON)
            .content(completeBody("x", phone, 1)),
        post(DEVICE + "/pairings/abandon").contentType(MediaType.APPLICATION_JSON)
            .content("{\"claimToken\":\"x\"}"))) {
      mvc.perform(unsigned).andExpect(status().isUnauthorized());
    }
    mvc.perform(asUser(post(DEVICE + "/pairings/claim"), shop.owner())
            .contentType(MediaType.APPLICATION_JSON).content(claimBody(code, phone)))
        .andExpect(status().isNotFound());
    mvc.perform(asUser(post(DEVICE + "/pairings/complete"), shop.owner())
            .contentType(MediaType.APPLICATION_JSON).content(completeBody("x", phone, 1)))
        .andExpect(status().isNotFound());
    mvc.perform(asUser(post(DEVICE + "/pairings/abandon"), shop.owner())
            .contentType(MediaType.APPLICATION_JSON).content("{\"claimToken\":\"x\"}"))
        .andExpect(status().isNotFound());
    PairingCode stored = template.findOne(Query.query(Criteria.where("shopId").is(shop.shopId())),
        PairingCode.class);
    assertEquals(PairingCodeStatus.UNUSED, stored.getStatus(), "the code is untouched");
    assertEquals(0, stored.getAttempts() == null ? 0 : stored.getAttempts(),
        "nothing counted on the code");
    assertEquals(0, template.count(Query.query(Criteria.where("shopId").is(shop.shopId())),
        DevicePairing.class), "no pairing");
    // the old proof exchange is gone on both sides
    mvc.perform(asUser(post(WORKER + "/pairing-link"), priya)
            .contentType(MediaType.APPLICATION_JSON).content("{\"proof\":\"p\"}"))
        .andExpect(status().isNotFound());

    // the only handshake: the worker's own login, same code
    String pairingId = pairWithLogin(priya, code, phone);
    assertEquals(employeeId, template.findById(pairingId, DevicePairing.class).getEmployeeId());
    assertEquals(0, template.count(Query.query(Criteria.where("shopId").is(shop.shopId())),
        PairingCode.class), "the used code is deleted");
    // a signed proof request on the paired phone is a device path with no handler
    mvc.perform(signed(pairingId, phone, post(DEVICE + "/pairings/me/proof"), null, null))
        .andExpect(status().isNotFound());
    assertFalse(template.collectionExists("hr_pairing_proofs")
        && template.getCollection("hr_pairing_proofs").countDocuments() > 0);
  }

  // ---- a punch never needs an account (R9.10, R9.4) ----------------------------------------------

  @Test
  void aPunchIsTheSameWhetherTheSessionIsLiveExpiredOrLoggedOut() throws Exception {
    ShopFixture shop = newShop("Harish Pai", "Pai Stores");
    Session ravi = activeWorker(shop, "Ravi Kumar", "9876500032");
    String employeeId = linkedEmployee(ravi).getId();
    Phone phone = Phone.create();
    String pairingId = pairWithLogin(ravi, createCode(shop, employeeId), phone);

    // live session: the first punch of the day. The two that follow land inside the HR-module
    // five-minute window, so their HR-module answer is DUPLICATE (stored, not counted); the point
    // is that the answer is the HR-module one whatever the session did meanwhile (R9.10).
    mvc.perform(signedPunch(pairingId, shop.shopId(), employeeId, phone))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.outcome").value("STORED"))
        .andExpect(jsonPath("$.data.direction").value("IN"));

    // expired session: the token's expiry is moved into the past (the save evicts the auth cache)
    UserAccount account = template.findById(ravi.userId(), UserAccount.class);
    account.getTokens().stream().filter(t -> ravi.token().equals(t.getAccessToken()))
        .forEach(t -> t.setExpiresAt(Instant.now().minus(1, ChronoUnit.MINUTES)));
    template.save(account);
    mvc.perform(asUser(get(WORKER + "/me"), ravi)).andExpect(status().isUnauthorized());
    mvc.perform(signedPunch(pairingId, shop.shopId(), employeeId, phone))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.outcome").value("DUPLICATE"))
        .andExpect(jsonPath("$.data.todayIn").isString());

    // logged out: the token is gone from the account altogether (R9.5)
    Session fresh = login(ravi);
    mvc.perform(asUser(post(AUTH + "/logout"), fresh)).andExpect(status().isOk());
    mvc.perform(asUser(get(WORKER + "/me"), fresh)).andExpect(status().isUnauthorized());
    mvc.perform(signedPunch(pairingId, shop.shopId(), employeeId, phone))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.outcome").value("DUPLICATE"))
        .andExpect(jsonPath("$.data.todayIn").isString());

    // three punches stored through the one pairing, none refused, whatever the session did
    List<Punch> punches = template.find(Query.query(Criteria.where("shopId").is(shop.shopId())
        .and("employeeId").is(employeeId)), Punch.class);
    assertEquals(3, punches.size());
    assertTrue(punches.stream().allMatch(p -> pairingId.equals(p.getPairingId())));
    // the pairing is still active: logging out keeps the device pairing (D9)
    assertEquals(PairingStatus.ACTIVE, template.findById(pairingId, DevicePairing.class)
        .getStatus());
    // and the worker, signed in again, sees all three in History (R7.9)
    Session again = login(ravi);
    mvc.perform(asUser(get(WORKER + "/punches"), again))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.length()").value(3));
  }

  // ---- Leave this shop (R5.3) -------------------------------------------------------------------

  @Test
  void leaveThisShopExitsTheEmployeeTodayAndFreesTheAccountForANewRequest() throws Exception {
    ShopFixture shop = newShop("Anil Mehta", "Mehta Stores");
    Session worker = activeWorker(shop, "Deepa Singh", "9876500041");
    Employee before = linkedEmployee(worker);

    MvcResult left = mvc.perform(asUser(post(WORKER + "/leave"), worker))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.state").value("NO_SHOP"))
        .andExpect(jsonPath("$.data.modules.length()").value(0))
        .andReturn();
    assertNull(JsonPath.read(json(left), "$.data.employee"));

    Employee exited = template.findById(before.getId(), Employee.class);
    assertEquals(EmployeeStatus.EXITED, exited.getStatus());
    assertEquals(today(), exited.getExitDate());
    assertEquals("self", exited.getUpdatedByUserId());
    assertEquals(worker.userId(), exited.getUserId(), "the link stays for payslips (R5.2)");
    assertNotNull(exited.getMembershipEndedAt(), "membership ended within the save (R5.2)");
    assertNull(template.findById(worker.userId(), UserAccount.class).getShopId());
    List<UserShopMembership> memberships = memberships(worker);
    assertEquals(1, memberships.size());
    assertFalse(memberships.get(0).isActive());
    assertNotNull(memberships.get(0).getEndedAt());

    me(worker, "NO_SHOP");
    // free to ask again (R5.2): the old request is decided, the account has no shop
    String again = join(worker, shop);
    assertNotEquals(before.getId(), again);
    me(worker, "PENDING");
    // ... and the old employee is a possible match for the approver (R4.3)
    mvc.perform(asUser(get(INBOX + "/" + again), shop.owner()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.hasPossibleMatch").value(true))
        .andExpect(jsonPath("$.data.possibleMatch.employeeId").value(before.getId()))
        .andExpect(jsonPath("$.data.possibleMatch.status").value("EXITED"))
        .andExpect(jsonPath("$.data.possibleMatch.linked").value(true));
  }

  // ---- an expired trial does not lock the worker out (design "Request paths") ---------------------

  @Test
  void aShopWithAnExpiredPlanStillAnswersTheWorkerButNotTheManagerInbox() throws Exception {
    ShopFixture shop = newShop("Geeta Patel", "Patel Mart");
    Session worker = activeWorker(shop, "Mohan Das", "9876500051");
    // the owner's screens work while the trial runs
    mvc.perform(asUser(get(INBOX), shop.owner())).andExpect(status().isOk());

    template.updateFirst(Query.query(Criteria.where("_id").is(shop.shopId())),
        new Update().set("planExpiryDate", Instant.now().minus(1, ChronoUnit.DAYS)), Shop.class);
    Instant expiry = template.findById(shop.shopId(), Shop.class).getPlanExpiryDate();
    assertTrue(expiry != null && expiry.isBefore(Instant.now()), "trial expired: " + expiry);

    // the manager's HR screens, the inbox included, are behind the plan check (402)
    mvc.perform(asUser(get(HR + "/employees"), shop.owner()))
        .andExpect(status().is(ErrorCode.PLAN_EXPIRED.getHttpStatus().value()));
    mvc.perform(asUser(get(INBOX), shop.owner()))
        .andExpect(status().is(ErrorCode.PLAN_EXPIRED.getHttpStatus().value()));

    // the worker's Home, History and Profile keep working
    me(worker, "ACTIVE");
    mvc.perform(asUser(get(WORKER + "/punches"), worker)).andExpect(status().isOk());
    mvc.perform(asUser(get(WORKER + "/profile"), worker)).andExpect(status().isOk());
    mvc.perform(asUser(get(WORKER + "/notifications"), worker)).andExpect(status().isOk());
  }

  // ---- helpers -----------------------------------------------------------------------------------

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

  private static String today() {
    return HrDateUtils.format(HrDateUtils.today());
  }
}
