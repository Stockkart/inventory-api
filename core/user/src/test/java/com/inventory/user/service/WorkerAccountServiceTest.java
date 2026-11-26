package com.inventory.user.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.inventory.common.exception.ValidationException;
import com.inventory.user.api.port.HrLinkedEmployeePort;
import com.inventory.user.domain.model.UserAccount;
import com.inventory.user.domain.model.UserRole;
import com.inventory.user.domain.model.UserShopMembership;
import com.inventory.user.domain.repository.UserAccountRepository;
import com.inventory.user.domain.repository.UserShopMembershipRepository;
import com.inventory.user.mapper.UserMapper;
import com.inventory.user.rest.dto.response.UserDto;
import com.mongodb.client.result.UpdateResult;
import java.util.List;
import java.util.Optional;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mapstruct.factory.Mappers;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

/** worker-app R5.1 (DD1), R5.2, R8.2, R2.5: the account side of a worker's shop. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class WorkerAccountServiceTest {

  @Mock private MongoTemplate mongoTemplate;
  @Mock private UserAccountRepository userAccountRepository;
  @Mock private UserShopMembershipRepository membershipRepository;
  @Mock private AuthCache authCache;
  @Mock private HrLinkedEmployeePort hrLinkedEmployeePort;
  @Spy private UserMapper userMapper = Mappers.getMapper(UserMapper.class);
  @InjectMocks private WorkerAccountService service;

  private static UserAccount account(String id, UserRole role, String shopId) {
    UserAccount a = new UserAccount();
    a.setUserId(id);
    a.setName("Priya Sharma");
    a.setEmail(id + "@example.com");
    a.setPhone("9876543210");
    a.setRole(role);
    a.setShopId(shopId);
    a.setActive(true);
    return a;
  }

  private static UserShopMembership membership(String userId, String shopId, UserRole role) {
    UserShopMembership m = new UserShopMembership();
    m.setUserId(userId);
    m.setShopId(shopId);
    m.setRole(role);
    m.setActive(true);
    return m;
  }

  // --- claimShop (DD1) ---

  @Test
  void claimWritesTheShopOntoAFreeWorkerAccountWithOneConditionalUpdate() {
    when(mongoTemplate.findAndModify(any(Query.class), any(Update.class),
        any(FindAndModifyOptions.class), eq(UserAccount.class)))
        .thenReturn(account("w-1", UserRole.WORKER, "shop-1"));

    assertTrue(service.claimShop("w-1", "shop-1"));

    ArgumentCaptor<Query> query = ArgumentCaptor.forClass(Query.class);
    ArgumentCaptor<Update> update = ArgumentCaptor.forClass(Update.class);
    verify(mongoTemplate).findAndModify(query.capture(), update.capture(),
        any(FindAndModifyOptions.class), eq(UserAccount.class));
    Document where = query.getValue().getQueryObject();
    assertEquals("w-1", where.get("_id"));
    assertEquals("WORKER", where.get("role"));
    assertTrue(where.containsKey("shopId"));
    assertNull(where.get("shopId"), "the lock: only an account with no shop is claimed");
    Document set = update.getValue().getUpdateObject().get("$set", Document.class);
    assertEquals("shop-1", set.get("shopId"));
    verify(authCache).evictUser("w-1");
    verify(userAccountRepository, never()).findById(any());
  }

  @Test
  void claimIsRefusedWhenAnotherShopHoldsTheAccount() {
    when(mongoTemplate.findAndModify(any(Query.class), any(Update.class),
        any(FindAndModifyOptions.class), eq(UserAccount.class))).thenReturn(null);
    when(userAccountRepository.findById("w-1"))
        .thenReturn(Optional.of(account("w-1", UserRole.WORKER, "shop-other")));

    assertFalse(service.claimShop("w-1", "shop-1"));
    verify(authCache).evictUser("w-1");
  }

  @Test
  void claimIsIdempotentForTheShopThatAlreadyHoldsTheAccount() {
    when(mongoTemplate.findAndModify(any(Query.class), any(Update.class),
        any(FindAndModifyOptions.class), eq(UserAccount.class))).thenReturn(null);
    when(userAccountRepository.findById("w-1"))
        .thenReturn(Optional.of(account("w-1", UserRole.WORKER, "shop-1")));

    assertTrue(service.claimShop("w-1", "shop-1"));
  }

  @Test
  void claimIsRefusedForAStaffAccountAndForAnUnknownOne() {
    when(mongoTemplate.findAndModify(any(Query.class), any(Update.class),
        any(FindAndModifyOptions.class), eq(UserAccount.class))).thenReturn(null);
    when(userAccountRepository.findById("m-1"))
        .thenReturn(Optional.of(account("m-1", UserRole.MANAGER, "shop-1")));
    when(userAccountRepository.findById("nobody")).thenReturn(Optional.empty());

    assertFalse(service.claimShop("m-1", "shop-1"), "a staff account's shopId is not a worker claim");
    assertFalse(service.claimShop("nobody", "shop-1"));
    assertFalse(service.claimShop(null, "shop-1"));
    assertFalse(service.claimShop("w-1", null));
  }

  // --- releaseShop (R5.2, R5.3) ---

  @Test
  void releaseClearsTheShopAndEndsTheWorkerMembership() {
    when(mongoTemplate.updateFirst(any(Query.class), any(Update.class), eq(UserAccount.class)))
        .thenReturn(UpdateResult.acknowledged(1, 1L, null));
    UserShopMembership m = membership("w-1", "shop-1", UserRole.WORKER);
    when(membershipRepository.findByUserIdAndShopIdAndActiveTrue("w-1", "shop-1"))
        .thenReturn(Optional.of(m));

    assertTrue(service.releaseShop("w-1", "shop-1"));

    ArgumentCaptor<Query> query = ArgumentCaptor.forClass(Query.class);
    ArgumentCaptor<Update> update = ArgumentCaptor.forClass(Update.class);
    verify(mongoTemplate).updateFirst(query.capture(), update.capture(), eq(UserAccount.class));
    Document where = query.getValue().getQueryObject();
    assertEquals("w-1", where.get("_id"));
    assertEquals("WORKER", where.get("role"));
    assertEquals("shop-1", where.get("shopId"), "only the shop that holds the account is released");
    assertTrue(update.getValue().getUpdateObject().get("$unset", Document.class).containsKey("shopId"));
    assertFalse(m.isActive());
    assertNotNull(m.getEndedAt());
    verify(membershipRepository).save(m);
    verify(authCache).evictUser("w-1");
  }

  @Test
  void releaseIsANoOpWhenTheAccountDoesNotHoldTheShopAndLeavesStaffMembershipsAlone() {
    when(mongoTemplate.updateFirst(any(Query.class), any(Update.class), eq(UserAccount.class)))
        .thenReturn(UpdateResult.acknowledged(0, 0L, null));
    UserShopMembership staff = membership("m-1", "shop-1", UserRole.MANAGER);
    when(membershipRepository.findByUserIdAndShopIdAndActiveTrue("m-1", "shop-1"))
        .thenReturn(Optional.of(staff));

    assertFalse(service.releaseShop("m-1", "shop-1"));

    assertTrue(staff.isActive());
    assertNull(staff.getEndedAt());
    verify(membershipRepository, never()).save(any());
  }

  // --- updateProfile (R8.2, R1.2) ---

  @Test
  void updateProfileStoresTheTrimmedNameAndTheNormalisedPhone() {
    UserAccount stored = account("w-1", UserRole.WORKER, "shop-1");
    when(userAccountRepository.findById("w-1")).thenReturn(Optional.of(stored));
    when(userAccountRepository.save(any(UserAccount.class))).thenAnswer(i -> i.getArgument(0));

    UserAccount saved = service.updateProfile("w-1", "  Priya S  ", "+91 98765-43211");

    assertEquals("Priya S", saved.getName());
    assertEquals("9876543211", saved.getPhone());
    assertEquals("w-1@example.com", saved.getEmail(), "the e-mail cannot be changed from the app");
    assertNotNull(saved.getUpdatedAt());
    verify(authCache).evictUser("w-1");
  }

  @Test
  void updateProfileRefusesABadNameOrPhoneAndChangesNothing() {
    assertThrows(ValidationException.class, () -> service.updateProfile("w-1", "P", "9876543210"));
    assertThrows(ValidationException.class,
        () -> service.updateProfile("w-1", "x".repeat(101), "9876543210"));
    assertThrows(ValidationException.class, () -> service.updateProfile("w-1", "Priya", "12345"));
    assertThrows(ValidationException.class, () -> service.updateProfile("w-1", "Priya", null));
    assertThrows(ValidationException.class, () -> service.updateProfile("w-1", "Priya", "  "));
    verify(userAccountRepository, never()).save(any());
    verify(userAccountRepository, never()).findById(any());
  }

  // --- updateProfile with the phone alone (R1.11, DD20) ---

  @Test
  void updateProfileWithoutANameKeepsTheStoredNameAndStoresTheNormalisedPhone() {
    for (String absent : new String[] {null, "", "   ", "\t\n"}) {
      UserAccount stored = account("w-1", UserRole.WORKER, null);
      when(userAccountRepository.findById("w-1")).thenReturn(Optional.of(stored));
      when(userAccountRepository.save(any(UserAccount.class))).thenAnswer(i -> i.getArgument(0));

      UserAccount saved = service.updateProfile("w-1", absent, "+91 98765 43299");

      assertEquals("Priya Sharma", saved.getName(), "name untouched when absent: '" + absent + "'");
      assertEquals("9876543299", saved.getPhone());
      assertNull(saved.getShopId(), "a worker without a shop can save a phone");
    }
    verify(authCache, org.mockito.Mockito.times(4)).evictUser("w-1");
  }

  @Test
  void updateProfileWithoutANameWorksForAStaffAccountToo() {
    UserAccount owner = account("o-1", UserRole.OWNER, "shop-1");
    when(userAccountRepository.findById("o-1")).thenReturn(Optional.of(owner));
    when(userAccountRepository.save(any(UserAccount.class))).thenAnswer(i -> i.getArgument(0));

    UserAccount saved = service.updateProfile("o-1", null, "9876543298");

    assertEquals("Priya Sharma", saved.getName());
    assertEquals("9876543298", saved.getPhone());
    assertEquals("shop-1", saved.getShopId(), "the shop is not touched");
    assertEquals(UserRole.OWNER, saved.getRole());
  }

  @Test
  void updateProfileWithoutANameStillRefusesABlankOrInvalidPhoneBeforeReading() {
    assertThrows(ValidationException.class, () -> service.updateProfile("w-1", null, null));
    assertThrows(ValidationException.class, () -> service.updateProfile("w-1", null, ""));
    assertThrows(ValidationException.class, () -> service.updateProfile("w-1", "", "   "));
    assertThrows(ValidationException.class, () -> service.updateProfile("w-1", null, "12345"));
    assertThrows(ValidationException.class, () -> service.updateProfile("w-1", null, "09876543210"));
    verify(userAccountRepository, never()).findById(any());
    verify(userAccountRepository, never()).save(any());
    verify(authCache, never()).evictUser(any());
  }

  // --- workersOf and linkedEmployeeCode (R2.5) ---

  @Test
  void workersOfListsWorkerMembersWithTheirLinkedEmployeeCode() {
    when(membershipRepository.findByShopIdAndActiveTrue("shop-1")).thenReturn(List.of(
        membership("o-1", "shop-1", UserRole.OWNER),
        membership("w-1", "shop-1", UserRole.WORKER),
        membership("w-2", "shop-1", UserRole.WORKER),
        membership("w-gone", "shop-1", UserRole.WORKER)));
    when(userAccountRepository.findById("o-1")).thenReturn(Optional.of(account("o-1", UserRole.OWNER, "shop-1")));
    when(userAccountRepository.findById("w-1")).thenReturn(Optional.of(account("w-1", UserRole.WORKER, "shop-1")));
    when(userAccountRepository.findById("w-2")).thenReturn(Optional.of(account("w-2", UserRole.WORKER, "shop-1")));
    when(userAccountRepository.findById("w-gone")).thenReturn(Optional.empty());
    when(hrLinkedEmployeePort.linkedEmployeeCode("shop-1", "w-1")).thenReturn("EMP-014");
    when(hrLinkedEmployeePort.linkedEmployeeCode("shop-1", "w-2")).thenReturn(null);

    List<UserDto> workers = service.workersOf("shop-1");

    assertEquals(2, workers.size());
    assertEquals("w-1", workers.get(0).getUserId());
    assertEquals(UserRole.WORKER, workers.get(0).getRole());
    assertEquals("w-1@example.com", workers.get(0).getEmail());
    assertEquals("EMP-014", workers.get(0).getLinkedEmployeeCode());
    assertEquals("w-2", workers.get(1).getUserId());
    assertNull(workers.get(1).getLinkedEmployeeCode());
    assertTrue(service.workersOf(null).isEmpty());
  }

  @Test
  void linkedEmployeeCodeIsNullWhenHrIsNotWiredOrFails() {
    when(hrLinkedEmployeePort.linkedEmployeeCode("shop-1", "w-1")).thenThrow(new RuntimeException("hr down"));
    assertNull(service.linkedEmployeeCode("shop-1", "w-1"));

    WorkerAccountService unwired = new WorkerAccountService();
    assertNull(unwired.linkedEmployeeCode("shop-1", "w-1"));
  }
}
