// Feature: worker-app, Property 2: A WORKER grants nothing on the staff side
package com.inventory.user.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.inventory.common.constants.ErrorCode;
import com.inventory.common.exception.BaseException;
import com.inventory.metrics.MetricsWrapper;
import com.inventory.user.domain.model.MemberModulePermissions;
import com.inventory.user.domain.model.MemberPermissions;
import com.inventory.user.domain.model.ProductSearchEditMode;
import com.inventory.user.domain.model.ShopRbacPolicyDocument;
import com.inventory.user.domain.model.UserAccount;
import com.inventory.user.domain.model.UserRole;
import com.inventory.user.domain.model.UserShopMembership;
import com.inventory.user.domain.repository.ShopRbacPolicyRepository;
import com.inventory.user.domain.repository.UserAccountRepository;
import com.inventory.user.domain.repository.UserShopMembershipRepository;
import com.inventory.user.rest.dto.response.ShopAccessResponse;
import com.inventory.user.validation.UserValidator;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tuple;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * The {@code core/user} half of design Property 2: for any {@link MemberPermissions} an owner may
 * have stored on a WORKER membership and any shop RBAC policy, the access {@link RbacService}
 * resolves for that membership has every module {@code false} (HR included), {@code owner =
 * false}, every team flag {@code false} except {@code viewMyInvitations}, no product-search edit
 * and no stock-correction approval; every guard built on that access ({@code requireModule},
 * {@code requireTeamAccess}, {@code canEditRates}, {@code requireStockCorrectionApproval}, {@code
 * canManageShopLayouts}, {@code validateProductSearchFieldUpdates}) refuses a WORKER with {@code
 * ACCESS_DENIED}; and {@link UserShopMembershipService#staffCountForShop} counts exactly the active
 * memberships whose role is not WORKER.
 *
 * <p>The HR half ({@code HrAccessService.rowFor(WORKER, grid)} is empty for any grid) lives in
 * {@code core/hr}, which depends on this module and not the other way round; {@code
 * HrAccessServiceTest} pins it by example.
 *
 * <p><b>Validates: Requirements 2.1, 2.3, 2.5</b>
 */
class RbacServiceWorkerProperties {

  private static final String WORKER_ID = "w1";
  private static final String SHOP_ID = "s1";

  static final List<String> ALL_MODULES = List.of(
      RbacService.MODULE_ACCOUNTING, RbacService.MODULE_ANALYTICS, RbacService.MODULE_MIS,
      RbacService.MODULE_TAXES, RbacService.MODULE_STOCK_CORRECTION, RbacService.MODULE_MARKETING,
      RbacService.MODULE_PAYMENT_PLAN, RbacService.MODULE_PRODUCT_SEARCH_EDIT,
      RbacService.MODULE_RATE_RULES_EDIT, RbacService.MODULE_HR);

  private static final List<String> MANAGE_TEAM_KEYS = List.of(
      RbacService.TEAM_MANAGE_INVITATIONS, RbacService.TEAM_MANAGE_JOIN_REQUESTS,
      RbacService.TEAM_MANAGE_SHOP_USERS);

  // --- the shop as the service sees it: one WORKER membership, stored overrides, a policy ---

  /** Everything an owner can store on the membership row plus the shop policy. */
  record WorkerShop(
      MemberPermissions stored,
      ProductSearchEditMode policy,
      String relationship,
      UserRole accountRole,
      String accountShopId) {

    RbacService rbacService() {
      UserAccount worker = new UserAccount();
      worker.setUserId(WORKER_ID);
      worker.setRole(accountRole);
      worker.setShopId(accountShopId);
      worker.setActive(true);

      UserShopMembership membership = new UserShopMembership();
      membership.setMembershipId("m1");
      membership.setUserId(WORKER_ID);
      membership.setShopId(SHOP_ID);
      membership.setRole(UserRole.WORKER);
      membership.setRelationship(relationship);
      membership.setActive(true);
      membership.setJoinedAt(Instant.EPOCH);
      membership.setPermissions(stored);

      ShopRbacPolicyRepository policies = mock(ShopRbacPolicyRepository.class);
      Optional<ShopRbacPolicyDocument> doc = Optional.empty();
      if (policy != null) {
        ShopRbacPolicyDocument d = new ShopRbacPolicyDocument();
        d.setShopId(SHOP_ID);
        d.setProductSearchEditMode(policy);
        doc = Optional.of(d);
      }
      when(policies.findByShopId(SHOP_ID)).thenReturn(doc);

      UserShopMembershipRepository memberships = mock(UserShopMembershipRepository.class);
      when(memberships.findByUserIdAndShopIdAndActiveTrue(WORKER_ID, SHOP_ID))
          .thenReturn(Optional.of(membership));
      when(memberships.findByShopIdAndActiveTrue(SHOP_ID)).thenReturn(List.of(membership));

      UserAccountRepository accounts = mock(UserAccountRepository.class);
      when(accounts.findById(WORKER_ID)).thenReturn(Optional.of(worker));

      UserShopMembershipService membershipService = mock(UserShopMembershipService.class);
      when(membershipService.hasAccess(WORKER_ID, SHOP_ID)).thenReturn(true);
      when(membershipService.hasOwnerAccess(anyString(), anyString())).thenReturn(false);

      RbacService service = new RbacService();
      ReflectionTestUtils.setField(service, "policyRepository", policies);
      ReflectionTestUtils.setField(service, "membershipRepository", memberships);
      ReflectionTestUtils.setField(service, "userAccountRepository", accounts);
      ReflectionTestUtils.setField(service, "membershipService", membershipService);
      ReflectionTestUtils.setField(service, "userValidator", new UserValidator());
      ReflectionTestUtils.setField(service, "metrics", mock(MetricsWrapper.class));
      return service;
    }
  }

  // --- arbitraries ---

  /** A per-member switch: explicitly on, explicitly off, or left to the role default. */
  @Provide
  Arbitrary<Boolean> switches() {
    return Arbitraries.of(Boolean.TRUE, Boolean.FALSE).injectNull(0.34);
  }

  /** Every combination of the ten module switches, with a bias towards "everything on". */
  @Provide
  Arbitrary<MemberModulePermissions> moduleOverrides() {
    Arbitrary<MemberModulePermissions> any = switches().list().ofSize(10).map(s ->
        new MemberModulePermissions(s.get(0), s.get(1), s.get(2), s.get(3), s.get(4), s.get(5),
            s.get(6), s.get(7), s.get(8), s.get(9)));
    MemberModulePermissions allOn = new MemberModulePermissions(
        true, true, true, true, true, true, true, true, true, true);
    return Arbitraries.frequencyOf(
        Tuple.of(4, any), Tuple.of(1, Arbitraries.just(allOn)));
  }

  /** Product-search field grants: core fields, the retired {@code count}, junk names, or none. */
  @Provide
  Arbitrary<Set<String>> fieldGrants() {
    Arbitrary<String> field = Arbitraries.frequencyOf(
        Tuple.of(6, Arbitraries.of(RbacService.CORE_PRODUCT_SEARCH_FIELDS)),
        Tuple.of(1, Arbitraries.just("count")),
        Tuple.of(1, Arbitraries.strings().alpha().ofMinLength(1).ofMaxLength(12)));
    return field.set().ofMaxSize(RbacService.CORE_PRODUCT_SEARCH_FIELDS.size() + 2)
        .map(LinkedHashSet::new);
  }

  /** What may sit in {@code UserShopMembership.permissions}: absent, empty, or any overrides. */
  @Provide
  Arbitrary<MemberPermissions> storedPermissions() {
    Arbitrary<MemberPermissions> filled =
        Combinators.combine(moduleOverrides().injectNull(0.2), fieldGrants().injectNull(0.2))
            .as(MemberPermissions::new);
    return filled.injectNull(0.1);
  }

  /** The shop's policy document: absent (defaults to FULL_EDIT) or either mode. */
  @Provide
  Arbitrary<ProductSearchEditMode> policies() {
    return Arbitraries.of(ProductSearchEditMode.class).injectNull(0.2);
  }

  /** A WORKER membership is created INVITED; a row saved without a relationship reads as INVITED. */
  @Provide
  Arbitrary<String> relationships() {
    return Arbitraries.of(UserShopMembershipService.RELATIONSHIP_INVITED).injectNull(0.2);
  }

  /** The account's own role: WORKER by construction, but the membership row decides. */
  @Provide
  Arbitrary<UserRole> accountRoles() {
    return Arbitraries.frequencyOf(
        Tuple.of(6, Arbitraries.just(UserRole.WORKER)),
        Tuple.of(1, Arbitraries.of(
            UserRole.OWNER, UserRole.ADMIN, UserRole.MANAGER, UserRole.CASHIER)))
        .injectNull(0.1);
  }

  @Provide
  Arbitrary<WorkerShop> workerShops() {
    return Combinators.combine(
            storedPermissions(),
            policies(),
            relationships(),
            accountRoles(),
            Arbitraries.of(SHOP_ID, "other-shop").injectNull(0.3))
        .as(WorkerShop::new);
  }

  /** The ten real module keys and some that do not exist. */
  @Provide
  Arbitrary<String> moduleKeys() {
    return Arbitraries.frequencyOf(
        Tuple.of(5, Arbitraries.of(ALL_MODULES)),
        Tuple.of(1, Arbitraries.strings().alpha().ofMinLength(1).ofMaxLength(20)));
  }

  /** Active memberships of one shop with any role, including none and WORKER. */
  @Provide
  Arbitrary<List<UserShopMembership>> activeMemberships() {
    Arbitrary<UserRole> role = Arbitraries.of(UserRole.class).injectNull(0.1);
    Arbitrary<UserShopMembership> membership = role.map(r -> {
      UserShopMembership m = new UserShopMembership();
      m.setShopId(SHOP_ID);
      m.setRole(r);
      m.setRelationship(r == UserRole.OWNER
          ? UserShopMembershipService.RELATIONSHIP_OWNER
          : UserShopMembershipService.RELATIONSHIP_INVITED);
      m.setActive(true);
      return m;
    });
    return membership.list().ofMaxSize(40).map(list -> {
      for (int i = 0; i < list.size(); i++) {
        list.get(i).setUserId("u" + i);
        list.get(i).setMembershipId("m" + i);
      }
      return list;
    });
  }

  // --- properties ---

  @Property(tries = 300)
  void aWorkerMembershipResolvesToNoModuleNoOwnershipAndNoTeamAccess(
      @ForAll("workerShops") WorkerShop shop) {
    ShopAccessResponse access = shop.rbacService().getEffectiveAccess(WORKER_ID, SHOP_ID);

    assertEquals(UserRole.WORKER.name(), access.getRole(), shop.toString());
    assertFalse(access.isOwner(), shop.toString());
    assertFalse(access.isCanManageAccess(), shop.toString());
    for (String module : ALL_MODULES) {
      assertEquals(Boolean.FALSE, access.getModules().get(module), module + " " + shop);
    }
    assertTrue(access.getModules().values().stream().noneMatch(Boolean.TRUE::equals),
        "no module at all is on: " + access.getModules() + " " + shop);

    ShopAccessResponse.TeamAccessDto team = access.getTeam();
    assertFalse(team.isManageInvitations(), shop.toString());
    assertFalse(team.isManageJoinRequests(), shop.toString());
    assertFalse(team.isManageShopUsers(), shop.toString());
    assertTrue(team.isViewMyInvitations(), shop.toString());

    assertFalse(access.getProductSearch().isCanEdit(), shop.toString());
    assertFalse(access.getProductSearch().isCanEditAll(), shop.toString());
    assertTrue(access.getProductSearch().getEditableFields().isEmpty(),
        "no field grant survives on a WORKER: " + access.getProductSearch() + " " + shop);
    assertFalse(access.getStockCorrection().isCanApprove(), shop.toString());
  }

  @Property(tries = 300)
  void requireModuleRefusesEveryKeyForAWorker(
      @ForAll("workerShops") WorkerShop shop, @ForAll("moduleKeys") String moduleKey) {
    RbacService service = shop.rbacService();

    BaseException e = assertThrows(BaseException.class,
        () -> service.requireModule(WORKER_ID, SHOP_ID, moduleKey), moduleKey + " " + shop);
    assertEquals(ErrorCode.ACCESS_DENIED, e.getErrorCode(), moduleKey + " " + shop);
  }

  @Property(tries = 200)
  void everyStaffGuardRefusesAWorkerAndOnlyViewMyInvitationsPasses(
      @ForAll("workerShops") WorkerShop shop, @ForAll("fieldGrants") Set<String> attempted) {
    RbacService service = shop.rbacService();

    for (String key : MANAGE_TEAM_KEYS) {
      BaseException e = assertThrows(BaseException.class,
          () -> service.requireTeamAccess(WORKER_ID, SHOP_ID, key), key + " " + shop);
      assertEquals(ErrorCode.ACCESS_DENIED, e.getErrorCode(), key + " " + shop);
    }
    assertDoesNotThrow(() -> service.requireTeamAccess(
        WORKER_ID, SHOP_ID, RbacService.TEAM_VIEW_MY_INVITATIONS), shop.toString());

    assertFalse(service.canEditRates(WORKER_ID, SHOP_ID), shop.toString());
    assertFalse(service.canManageShopLayouts(WORKER_ID, SHOP_ID), shop.toString());
    assertEquals(ErrorCode.ACCESS_DENIED, assertThrows(BaseException.class,
        () -> service.requireStockCorrectionApproval(WORKER_ID, SHOP_ID), shop.toString())
        .getErrorCode());
    assertEquals(ErrorCode.ACCESS_DENIED, assertThrows(BaseException.class,
        () -> service.requireShopLayoutManagement(WORKER_ID, SHOP_ID), shop.toString())
        .getErrorCode());
    if (!attempted.isEmpty()) {
      assertEquals(ErrorCode.ACCESS_DENIED, assertThrows(BaseException.class,
          () -> service.validateProductSearchFieldUpdates(WORKER_ID, SHOP_ID, attempted),
          attempted + " " + shop).getErrorCode());
    }
  }

  @Property(tries = 200)
  void staffCountForShopCountsExactlyTheActiveNonWorkerMemberships(
      @ForAll("activeMemberships") List<UserShopMembership> memberships) {
    UserShopMembershipRepository repository = mock(UserShopMembershipRepository.class);
    when(repository.findByShopIdAndActiveTrue(SHOP_ID)).thenReturn(memberships);
    UserShopMembershipService service = new UserShopMembershipService();
    ReflectionTestUtils.setField(service, "membershipRepository", repository);

    long expected = memberships.stream().filter(m -> m.getRole() != UserRole.WORKER).count();

    assertEquals(expected, service.staffCountForShop(SHOP_ID), memberships.toString());
  }
}
