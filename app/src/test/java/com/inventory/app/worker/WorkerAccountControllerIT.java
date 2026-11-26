package com.inventory.app.worker;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.inventory.common.constants.ErrorCode;
import com.inventory.plan.domain.model.Plan;
import com.inventory.user.domain.model.UserAccount;
import com.inventory.user.domain.model.UserPhoto;
import com.inventory.user.domain.model.UserRole;
import com.inventory.user.service.UserShopMembershipService;
import com.jayway.jsonpath.JsonPath;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * The account's own profile and photo through the real chain (worker-app R1.2, R1.7, R2.5, R8.2,
 * R11.5): {@code PUT /users/me/profile}, {@code PUT|DELETE /users/me/photo}, {@code GET
 * /users/{id}/photo} under the who-may-see rule, and the plan's seat count leaving workers out.
 * Every request carries a real Bearer token minted by {@code POST /auth/signup}; the photo bytes
 * are real JPEG and PNG images written by {@code ImageIO}. See {@link WorkerAppItSupport}.
 */
@EnabledIfSystemProperty(named = WorkerAppItSupport.MONGO_PROPERTY, matches = ".+")
class WorkerAccountControllerIT extends WorkerAppItSupport {

  static final String USERS = "/api/v1/users";

  @Autowired private UserShopMembershipService membershipService;

  // ---- profile -----------------------------------------------------------------------------------

  /** Name and phone change under the sign-up rules (R1.2, R8.2); e-mail, role and shop do not. */
  @Test
  void profileEditChangesNameAndPhoneUnderTheSignupRulesAndNothingElse() throws Exception {
    Session worker = signupWorker("Priya Sharma", "9876500071");

    mvc.perform(profile(worker, "  Priya Sharma-Rao  ", "+91 98765-00072"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.data.userId").value(worker.userId()))
        .andExpect(jsonPath("$.data.name").value("Priya Sharma-Rao"))
        .andExpect(jsonPath("$.data.phone").value("9876500072"))
        .andExpect(jsonPath("$.data.email").value(worker.email()))
        .andExpect(jsonPath("$.data.role").value("WORKER"))
        .andExpect(jsonPath("$.data.shopId").doesNotExist());
    UserAccount stored = template.findById(worker.userId(), UserAccount.class);
    assertEquals("Priya Sharma-Rao", stored.getName());
    assertEquals("9876500072", stored.getPhone());
    assertEquals(UserRole.WORKER, stored.getRole());
    assertNull(stored.getShopId(), "a profile edit never gives a worker a shop");
    assertEquals(worker.email(), stored.getEmail());

    // the token still works, and the app's Home sees the new name on its next focus (cache evicted)
    String me = me(worker, "NO_SHOP");
    assertEquals("Priya Sharma-Rao", JsonPath.read(me, "$.data.account.name"));
    assertEquals("9876500072", JsonPath.read(me, "$.data.account.phone"));

    // the R1.2 rules: a one-letter name, a number that is not an Indian mobile, no phone at all
    // (a name that is absent is not a mistake any more: see profileWithThePhoneAloneKeepsTheName)
    for (String[] bad : List.of(
        new String[] {"P", "9876500072"},
        new String[] {" P ", "9876500072"},
        new String[] {"a".repeat(101), "9876500072"},
        new String[] {"Priya", "12345"},
        new String[] {"Priya", "09876500072"},
        new String[] {"Priya", "+1 98765 00072"},
        new String[] {"Priya", null})) {
      mvc.perform(profile(worker, bad[0], bad[1]))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.success").value(false))
          .andExpect(jsonPath("$.data.status").value(400))
          .andExpect(jsonPath("$.data.errors.general[0]").isString());
    }
    UserAccount unchanged = template.findById(worker.userId(), UserAccount.class);
    assertEquals("Priya Sharma-Rao", unchanged.getName(), "a refused edit changes nothing");
    assertEquals("9876500072", unchanged.getPhone());

    // the boundary values are accepted: two characters, one hundred characters, a plain 10 digits
    mvc.perform(profile(worker, "Ab", "9876500073")).andExpect(status().isOk())
        .andExpect(jsonPath("$.data.name").value("Ab"))
        .andExpect(jsonPath("$.data.phone").value("9876500073"));
    mvc.perform(profile(worker, "b".repeat(100), "919876500074")).andExpect(status().isOk())
        .andExpect(jsonPath("$.data.name").value("b".repeat(100)))
        .andExpect(jsonPath("$.data.phone").value("9876500074"));

    // without a token: not authenticated, nothing changes
    mvc.perform(put(USERS + "/me/profile").contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"Someone Else\",\"phone\":\"9876500075\"}"))
        .andExpect(status().isUnauthorized());
    assertEquals("b".repeat(100), template.findById(worker.userId(), UserAccount.class).getName());
  }

  /** The path acts on the token's user only: a staff account edits itself and keeps its shop. */
  @Test
  void aStaffAccountEditsItsOwnProfileAndKeepsItsShop() throws Exception {
    ShopFixture shop = newShop("Vikram Shah", "Shah Stores");

    mvc.perform(profile(shop.owner(), "Vikram S. Shah", "9876500076"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.userId").value(shop.owner().userId()))
        .andExpect(jsonPath("$.data.name").value("Vikram S. Shah"))
        .andExpect(jsonPath("$.data.phone").value("9876500076"))
        .andExpect(jsonPath("$.data.role").value("OWNER"))
        .andExpect(jsonPath("$.data.shopId").value(shop.shopId()));
    UserAccount stored = template.findById(shop.owner().userId(), UserAccount.class);
    assertEquals(shop.shopId(), stored.getShopId());
    assertEquals(UserRole.OWNER, stored.getRole());
    // the owner's shop screens still work with the same token
    mvc.perform(asUser(get(HR + "/employees"), shop.owner())).andExpect(status().isOk());
  }

  // ---- profile: the phone alone (R1.11, R1.12, DD20) ---------------------------------------------

  /**
   * The Stockkart Team app's phone step sends {@code {phone}} with no {@code name}: the name stays,
   * the normalised phone is stored, for every role and every shop state (a WORKER with no shop, a
   * WORKER in a shop, a staff account with no employee). A blank or invalid phone is 400 and changes
   * nothing; {@code {name, phone}} still changes both.
   */
  @Test
  void profileWithThePhoneAloneKeepsTheNameForEveryRoleAndShopState() throws Exception {
    ShopFixture shop = newShop("Kiran Mehta", "Mehta Traders");
    Session noShop = signupWorker("Lakshmi Nair", "9876500111");
    Session inShop = activeWorker(shop, "Suresh Pillai", "9876500112");
    Session staff = staffMember(shop, "Deepa Iyer", UserRole.MANAGER);
    me(staff, "STAFF_NO_EMPLOYEE");

    record Case(Session session, String name, String state) {}
    for (Case c : List.of(
        new Case(noShop, "Lakshmi Nair", "NO_SHOP"),
        new Case(inShop, "Suresh Pillai", "ACTIVE"),
        new Case(staff, "Deepa Iyer", "STAFF_NO_EMPLOYEE"))) {
      UserAccount before = template.findById(c.session().userId(), UserAccount.class);

      // no name key at all, as the app sends it; +91 and spaces are stripped
      mvc.perform(phoneOnly(c.session(), "+91 98765 00120"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.success").value(true))
          .andExpect(jsonPath("$.data.userId").value(c.session().userId()))
          .andExpect(jsonPath("$.data.name").value(c.name()))
          .andExpect(jsonPath("$.data.phone").value("9876500120"))
          .andExpect(jsonPath("$.data.role").value(before.getRole().name()));
      // an explicit null and a blank name mean the same
      mvc.perform(profile(c.session(), null, "9876500121")).andExpect(status().isOk())
          .andExpect(jsonPath("$.data.name").value(c.name()))
          .andExpect(jsonPath("$.data.phone").value("9876500121"));
      mvc.perform(profile(c.session(), "   ", "919876500122")).andExpect(status().isOk())
          .andExpect(jsonPath("$.data.name").value(c.name()))
          .andExpect(jsonPath("$.data.phone").value("9876500122"));
      UserAccount stored = template.findById(c.session().userId(), UserAccount.class);
      assertEquals(c.name(), stored.getName(), "name untouched for " + c.state());
      assertEquals("9876500122", stored.getPhone());
      assertEquals(before.getRole(), stored.getRole());
      assertEquals(before.getShopId(), stored.getShopId(), "the shop is untouched for " + c.state());
      assertEquals(before.getEmail(), stored.getEmail());
      // the cache is evicted: the app's next /me sees the new phone and the same state
      String me = me(c.session(), c.state());
      assertEquals("9876500122", JsonPath.read(me, "$.data.account.phone"));
      assertEquals(c.name(), JsonPath.read(me, "$.data.account.name"));

      // a blank or an invalid phone: 400, and nothing changes
      for (String bad : new String[] {"", "   ", "12345", "09876500123", "+1 98765 00123", null}) {
        mvc.perform(bad == null ? asUser(put(USERS + "/me/profile"), c.session())
                .contentType(MediaType.APPLICATION_JSON).content("{}")
                : phoneOnly(c.session(), bad))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.data.status").value(400))
            .andExpect(jsonPath("$.data.errors.general[0]").isString());
      }
      UserAccount unchanged = template.findById(c.session().userId(), UserAccount.class);
      assertEquals(c.name(), unchanged.getName());
      assertEquals("9876500122", unchanged.getPhone(), "a refused phone changes nothing");

      // name and phone together still change both (R8.2)
      mvc.perform(profile(c.session(), c.name() + " II", "9876500124")).andExpect(status().isOk())
          .andExpect(jsonPath("$.data.name").value(c.name() + " II"))
          .andExpect(jsonPath("$.data.phone").value("9876500124"));
    }

    // the staff path the app does not use for this: still HR_WORKER_ONLY for a staff account, which
    // is why the phone step saves through /users/me/profile
    expectRefusal(asUser(put(WORKER + "/profile"), staff).contentType(MediaType.APPLICATION_JSON)
        .content("{\"name\":\"Deepa Iyer\",\"phone\":\"9876500125\"}"), ErrorCode.HR_WORKER_ONLY);
    // the worker in a shop: the account changed, the linked employee's phone did not (the phone
    // step touches the account only; Edit details is the way to change both)
    assertEquals("9876500112", template.findOne(Query.query(Criteria.where("shopId")
            .is(shop.shopId()).and("userId").is(inShop.userId())),
        com.inventory.hr.domain.model.Employee.class).getPhone());
  }

  // ---- photo: put, get, delete -------------------------------------------------------------------

  /** A JPEG or PNG ≤ 300 KB and ≤ 512 × 512 is stored as sent and read back; anything else is refused (R1.7). */
  @Test
  void photoPutGetAndDeleteOnTheOwnAccount() throws Exception {
    Session worker = signupWorker("Sunita Devi", "9876500081");
    String ownUrl = USERS + "/" + worker.userId() + "/photo";

    // nothing yet: the owner of the account sees "none", not "forbidden"
    mvc.perform(asUser(get(USERS + "/me/photo"), worker)).andExpect(status().isNotFound());
    mvc.perform(asUser(get(ownUrl), worker)).andExpect(status().isNotFound());

    byte[] jpeg = image(64, 48, "jpeg");
    mvc.perform(putPhoto(worker, jpeg, "me.jpg", "image/jpeg"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.data.photoUrl").value(ownUrl))
        .andExpect(jsonPath("$.data.contentType").value("image/jpeg"))
        .andExpect(jsonPath("$.data.bytes").value(jpeg.length))
        .andExpect(jsonPath("$.data.updatedAt").isString());
    UserPhoto stored = template.findById(worker.userId(), UserPhoto.class);
    assertNotNull(stored, "one row in user_photos keyed by the account");
    assertEquals("image/jpeg", stored.getContentType());
    assertArrayEquals(jpeg, stored.getBytes(), "stored as sent, never resized");

    // read back by "me" and by the id, as image bytes with a private 5-minute cache
    for (String path : List.of(USERS + "/me/photo", ownUrl)) {
      MvcResult r = mvc.perform(asUser(get(path), worker))
          .andExpect(status().isOk())
          .andExpect(header().string("Content-Type", "image/jpeg"))
          .andReturn();
      String cacheControl = r.getResponse().getHeader("Cache-Control");
      assertNotNull(cacheControl, path);
      assertTrue(cacheControl.contains("private"), cacheControl);
      assertTrue(cacheControl.contains("max-age=300"), cacheControl);
      assertArrayEquals(jpeg, r.getResponse().getContentAsByteArray(), path);
    }
    // the app's Home now carries the URL (the one place a photo is pointed at, R11.5)
    assertEquals(ownUrl, JsonPath.read(me(worker, "NO_SHOP"), "$.data.account.photoUrl"));

    // a PNG at the very limit replaces it; the stored type is what the bytes are
    byte[] png = image(512, 512, "png");
    assertTrue(png.length <= 300 * 1024, "the fixture itself fits: " + png.length);
    mvc.perform(putPhoto(worker, png, "me.png", "image/png"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.contentType").value("image/png"))
        .andExpect(jsonPath("$.data.bytes").value(png.length));
    mvc.perform(asUser(get(USERS + "/me/photo"), worker))
        .andExpect(status().isOk())
        .andExpect(header().string("Content-Type", "image/png"));

    // refused: too wide, too tall, not an image, a GIF, over 300 KB, an empty part
    byte[] oversize = new byte[300 * 1024 + 1];
    for (MockMultipartFile bad : List.of(
        new MockMultipartFile("photo", "wide.png", "image/png", image(513, 10, "png")),
        new MockMultipartFile("photo", "tall.jpg", "image/jpeg", image(10, 513, "jpeg")),
        new MockMultipartFile("photo", "text.jpg", "image/jpeg",
            "this is not a picture".getBytes(StandardCharsets.UTF_8)),
        new MockMultipartFile("photo", "anim.gif", "image/gif", image(16, 16, "gif")),
        new MockMultipartFile("photo", "big.jpg", "image/jpeg", oversize),
        new MockMultipartFile("photo", "empty.jpg", "image/jpeg", new byte[0]))) {
      mvc.perform(asUser(multipart(HttpMethod.PUT, USERS + "/me/photo").file(bad), worker))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.success").value(false))
          .andExpect(jsonPath("$.data.errors.general[0]").isString());
    }
    // ... and a refusal leaves the previous photo in place
    MvcResult still = mvc.perform(asUser(get(USERS + "/me/photo"), worker))
        .andExpect(status().isOk())
        .andExpect(header().string("Content-Type", "image/png"))
        .andReturn();
    assertArrayEquals(png, still.getResponse().getContentAsByteArray());

    // without a token nothing is stored or shown
    mvc.perform(multipart(HttpMethod.PUT, USERS + "/me/photo")
            .file(new MockMultipartFile("photo", "x.jpg", "image/jpeg", jpeg)))
        .andExpect(status().isUnauthorized());
    mvc.perform(get(ownUrl)).andExpect(status().isUnauthorized());
    mvc.perform(delete(USERS + "/me/photo")).andExpect(status().isUnauthorized());
    assertArrayEquals(png, template.findById(worker.userId(), UserPhoto.class).getBytes());

    // delete: gone for both reads, the row is gone, a second delete is a quiet success
    mvc.perform(asUser(delete(USERS + "/me/photo"), worker))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.deleted").value(true));
    assertNull(template.findById(worker.userId(), UserPhoto.class));
    mvc.perform(asUser(get(USERS + "/me/photo"), worker)).andExpect(status().isNotFound());
    mvc.perform(asUser(get(ownUrl), worker)).andExpect(status().isNotFound());
    assertNull(JsonPath.read(me(worker, "NO_SHOP"), "$.data.account.photoUrl"));
    mvc.perform(asUser(delete(USERS + "/me/photo"), worker))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.deleted").value(true));
  }

  // ---- photo: who may see it ---------------------------------------------------------------------

  /**
   * The who-may-see rule (R11.5): the account itself; an approver of a shop where the account has a
   * Pending or recently Rejected request; a co-member of an active shop. Everyone else is refused
   * before existence is looked up, so a stranger cannot even learn whether there is a photo.
   */
  @Test
  void aPhotoIsSeenByTheAccountItsApproversAndItsCoMembersOnly() throws Exception {
    ShopFixture shopA = newShop("Pooja Reddy", "Reddy Stores");
    ShopFixture stranger = newShop("Harish Gupta", "Gupta Stores");
    Session worker = signupWorker("Arjun Rao", "9876500091");
    Session bystander = signupWorker("Nikhil Jain", "9876500092");
    byte[] jpeg = image(32, 32, "jpeg");
    mvc.perform(putPhoto(worker, jpeg, "me.jpg", "image/jpeg")).andExpect(status().isOk());
    String url = USERS + "/" + worker.userId() + "/photo";

    // before any request exists: only the account itself
    expectPhoto(url, worker, jpeg);
    expectDenied(asUser(get(url), shopA.owner()), ErrorCode.ACCESS_DENIED);
    expectDenied(asUser(get(url), stranger.owner()), ErrorCode.ACCESS_DENIED);
    expectDenied(asUser(get(url), bystander), ErrorCode.ACCESS_DENIED);

    // a pending request opens the photo to that shop's approver, and the inbox points at it
    String requestId = join(worker, shopA);
    expectPhoto(url, shopA.owner(), jpeg);
    mvc.perform(asUser(get(INBOX).param("status", "PENDING"), shopA.owner()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.items[0].id").value(requestId))
        .andExpect(jsonPath("$.data.items[0].photoUrl").value(url));
    mvc.perform(asUser(get(INBOX + "/" + requestId), shopA.owner()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.photoUrl").value(url));
    // a Cashier of the same shop holds no "add and edit employees": not an approver, not yet a
    // co-member
    Session cashier = staffMember(shopA, "Neha Kapoor", UserRole.CASHIER);
    expectDenied(asUser(get(url), cashier), ErrorCode.ACCESS_DENIED);
    expectDenied(asUser(get(url), stranger.owner()), ErrorCode.ACCESS_DENIED);
    expectDenied(asUser(get(url), bystander), ErrorCode.ACCESS_DENIED);

    // approval makes the worker an active member: every active member of the shop may see it now
    approveCreate(shopA, requestId);
    me(worker, "ACTIVE");
    expectPhoto(url, shopA.owner(), jpeg);
    expectPhoto(url, cashier, jpeg);
    Session coWorker = activeWorker(shopA, "Mohan Das", "9876500093");
    expectPhoto(url, coWorker, jpeg);
    expectDenied(asUser(get(url), stranger.owner()), ErrorCode.ACCESS_DENIED);
    expectDenied(asUser(get(url), bystander), ErrorCode.ACCESS_DENIED);
    // and the worker, a co-member too, may see the owner's photo once there is one
    byte[] ownerJpeg = image(24, 24, "jpeg");
    mvc.perform(putPhoto(shopA.owner(), ownerJpeg, "owner.jpg", "image/jpeg"))
        .andExpect(status().isOk());
    expectPhoto(USERS + "/" + shopA.owner().userId() + "/photo", worker, ownerJpeg);
    expectDenied(asUser(get(USERS + "/" + shopA.owner().userId() + "/photo"), bystander),
        ErrorCode.ACCESS_DENIED);

    // a rejected request keeps the photo visible to the approver (≤ 90 days), to nobody else
    Session rejected = signupWorker("Geeta Patel", "9876500094");
    byte[] rejectedJpeg = image(20, 20, "jpeg");
    mvc.perform(putPhoto(rejected, rejectedJpeg, "me.jpg", "image/jpeg"))
        .andExpect(status().isOk());
    String rejectedUrl = USERS + "/" + rejected.userId() + "/photo";
    String rejectedRequest = join(rejected, shopA);
    mvc.perform(asUser(post(INBOX + "/" + rejectedRequest + "/reject"), shopA.owner())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"reason\":\"Not hiring now\"}"))
        .andExpect(status().isOk());
    expectPhoto(rejectedUrl, shopA.owner(), rejectedJpeg);
    expectDenied(asUser(get(rejectedUrl), cashier), ErrorCode.ACCESS_DENIED);
    expectDenied(asUser(get(rejectedUrl), stranger.owner()), ErrorCode.ACCESS_DENIED);
    expectDenied(asUser(get(rejectedUrl), worker), ErrorCode.ACCESS_DENIED);

    // a withdrawn request opens nothing
    Session withdrawn = signupWorker("Ravi Kumar", "9876500095");
    mvc.perform(putPhoto(withdrawn, image(20, 20, "jpeg"), "me.jpg", "image/jpeg"))
        .andExpect(status().isOk());
    String withdrawnRequest = join(withdrawn, shopA);
    mvc.perform(asUser(post(WORKER + "/join-requests/" + withdrawnRequest + "/withdraw"), withdrawn))
        .andExpect(status().isOk());
    expectDenied(asUser(get(USERS + "/" + withdrawn.userId() + "/photo"), shopA.owner()),
        ErrorCode.ACCESS_DENIED);

    // access is decided before existence: a pending requester with no photo is "none" to the
    // approver and "forbidden" to everyone else
    join(bystander, stranger);
    mvc.perform(asUser(get(USERS + "/" + bystander.userId() + "/photo"), stranger.owner()))
        .andExpect(status().isNotFound());
    expectDenied(asUser(get(USERS + "/" + bystander.userId() + "/photo"), shopA.owner()),
        ErrorCode.ACCESS_DENIED);
    expectDenied(asUser(get(USERS + "/" + bystander.userId() + "/photo"), worker),
        ErrorCode.ACCESS_DENIED);
    // an id that is nobody's is forbidden too, not "not found"
    expectDenied(asUser(get(USERS + "/64b000000000000000000001/photo"), shopA.owner()),
        ErrorCode.ACCESS_DENIED);
  }

  // ---- seats -------------------------------------------------------------------------------------

  /**
   * Workers are members of the shop but not staff seats (R2.5): the Users list shows them with
   * their employee code, the seat count and the plan's "user limit reached" leave them out.
   */
  @Test
  void workersAreListedWithTheirEmployeeCodeAndNeverCountedAsSeats() throws Exception {
    ShopFixture shop = newShop("Anita Desai", "Desai Mart");
    Session w1 = activeWorker(shop, "Worker One", "9876500101");
    Session w2 = activeWorker(shop, "Worker Two", "9876500102");
    Session w3 = activeWorker(shop, "Worker Three", "9876500103");

    // the Users page: the owner and three WORKER rows, each with the code the approval gave
    MvcResult r = mvc.perform(asUser(get("/api/v1/shops/" + shop.shopId() + "/users"), shop.owner()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.data.length()").value(4))
        .andReturn();
    String json = json(r);
    List<String> workerIds = JsonPath.read(json, "$.data.data[?(@.role == 'WORKER')].userId");
    assertEquals(Set.of(w1.userId(), w2.userId(), w3.userId()), Set.copyOf(workerIds));
    List<String> codes = JsonPath.read(json, "$.data.data[?(@.role == 'WORKER')].linkedEmployeeCode");
    assertEquals(3, codes.size());
    codes.forEach(code -> assertTrue(code != null && !code.isBlank(), "a linked employee code"));
    assertEquals(3, Set.copyOf(codes).size(), "three distinct employees");
    List<String> owners = JsonPath.read(json, "$.data.data[?(@.role == 'OWNER')].userId");
    assertEquals(List.of(shop.owner().userId()), owners);
    List<Object> staffCodes = JsonPath.read(json, "$.data.data[?(@.role == 'OWNER')].linkedEmployeeCode");
    assertTrue(staffCodes.isEmpty() || staffCodes.get(0) == null, "a staff row has no employee code");

    // the seat count against the real rows: four active memberships, one seat
    assertEquals(4, template.count(Query.query(Criteria.where("shopId").is(shop.shopId())
        .and("active").is(true)), com.inventory.user.domain.model.UserShopMembership.class));
    assertEquals(1, membershipService.staffCountForShop(shop.shopId()));

    // the plan status the web reads: with a two-seat plan, the owner and three workers are under
    // the limit; a Cashier fills the second seat
    template.upsert(Query.query(Criteria.where("planName").is("Base")),
        new Update().set("userLimit", 2).set("unlimited", false), Plan.class);
    mvc.perform(asUser(get("/api/v1/plans/shop/status"), shop.owner()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.userLimitReached").value(false));
    staffMember(shop, "Cash Ier", UserRole.CASHIER);
    assertEquals(2, membershipService.staffCountForShop(shop.shopId()));
    mvc.perform(asUser(get("/api/v1/plans/shop/status"), shop.owner()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.userLimitReached").value(true));
    // one more worker changes nothing about seats
    activeWorker(shop, "Worker Four", "9876500104");
    assertEquals(2, membershipService.staffCountForShop(shop.shopId()));
    assertFalse(workersOf(shop).isEmpty());
    assertEquals(4, workersOf(shop).size());
  }

  // ---- helpers -----------------------------------------------------------------------------------

  private MockHttpServletRequestBuilder profile(Session s, String name, String phone) {
    String body = "{"
        + (name != null ? "\"name\":\"" + name + "\"" : "\"name\":null")
        + ","
        + (phone != null ? "\"phone\":\"" + phone + "\"" : "\"phone\":null")
        + "}";
    return asUser(put(USERS + "/me/profile"), s).contentType(MediaType.APPLICATION_JSON)
        .content(body);
  }

  /** The body the app's phone step sends: {@code {phone}} and no {@code name} key at all. */
  private MockHttpServletRequestBuilder phoneOnly(Session s, String phone) {
    return asUser(put(USERS + "/me/profile"), s).contentType(MediaType.APPLICATION_JSON)
        .content("{\"phone\":\"" + phone + "\"}");
  }

  private MockHttpServletRequestBuilder putPhoto(Session s, byte[] bytes, String fileName,
      String contentType) {
    return asUser(multipart(HttpMethod.PUT, USERS + "/me/photo")
        .file(new MockMultipartFile("photo", fileName, contentType, bytes)), s);
  }

  /** {@code GET url} as {@code viewer}: 200, image bytes equal to {@code expected}. */
  private void expectPhoto(String url, Session viewer, byte[] expected) throws Exception {
    MvcResult r = mvc.perform(asUser(get(url), viewer))
        .andExpect(status().isOk())
        .andExpect(header().string("Content-Type", "image/jpeg"))
        .andReturn();
    assertArrayEquals(expected, r.getResponse().getContentAsByteArray(), url);
  }

  private List<String> workersOf(ShopFixture shop) {
    return template.find(Query.query(Criteria.where("shopId").is(shop.shopId())
            .and("active").is(true).and("role").is(UserRole.WORKER.name())),
        com.inventory.user.domain.model.UserShopMembership.class)
        .stream().map(com.inventory.user.domain.model.UserShopMembership::getUserId).toList();
  }

  /** A real {@code format} image of {@code width} × {@code height}, as the phone would upload. */
  static byte[] image(int width, int height, String format) throws Exception {
    BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
    Graphics2D g = img.createGraphics();
    g.setColor(new Color(0x2E, 0x7D, 0x32));
    g.fillRect(0, 0, width, height);
    g.setColor(Color.WHITE);
    g.fillOval(width / 4, height / 4, Math.max(1, width / 2), Math.max(1, height / 2));
    g.dispose();
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    assertTrue(ImageIO.write(img, format, out), "ImageIO writes " + format);
    return out.toByteArray();
  }
}
