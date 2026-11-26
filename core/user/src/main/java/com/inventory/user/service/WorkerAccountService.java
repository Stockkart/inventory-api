package com.inventory.user.service;

import com.inventory.common.constants.ErrorCode;
import com.inventory.common.exception.BaseException;
import com.inventory.common.exception.ResourceNotFoundException;
import com.inventory.common.exception.ValidationException;
import com.inventory.user.api.port.HrLinkedEmployeePort;
import com.inventory.user.domain.model.UserAccount;
import com.inventory.user.domain.model.UserRole;
import com.inventory.user.domain.model.UserShopMembership;
import com.inventory.user.domain.repository.UserAccountRepository;
import com.inventory.user.domain.repository.UserShopMembershipRepository;
import com.inventory.user.mapper.UserMapper;
import com.inventory.user.rest.dto.response.UserDto;
import com.inventory.user.validation.AuthValidator;
import com.mongodb.client.result.UpdateResult;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

/**
 * The account side of a worker's life in a shop (worker-app R5.1, R8.2, R2.5).
 *
 * <p>A WORKER account's one shop is {@code UserAccount.shopId} (design DD1): {@link #claimShop}
 * writes it with one conditional {@code findAndModify}, which is the lock that keeps "one shop at a
 * time" true when two shops approve the same worker at the same moment; {@link #releaseShop} clears
 * it and ends the membership when the worker exits or leaves. Both are called from HR (approval
 * transaction, exit job); neither reads an employee.
 *
 * <p>The conditional updates bypass the repository save events, so every write here evicts {@link
 * AuthCache} for the user itself ({@code AuthCacheEvictionListener} does not see {@code
 * findAndModify}).
 */
@Slf4j
@Service
public class WorkerAccountService {

  @Autowired private MongoTemplate mongoTemplate;

  @Autowired private UserAccountRepository userAccountRepository;

  @Autowired private UserShopMembershipRepository membershipRepository;

  @Autowired private UserMapper userMapper;

  @Autowired(required = false)
  private AuthCache authCache;

  /** Own-module port, implemented by HR (task 4.3); absent while HR has not wired it. */
  @Autowired(required = false)
  private HrLinkedEmployeePort hrLinkedEmployeePort;

  /**
   * Makes {@code shopId} the WORKER account's one shop, if it has none yet (DD1).
   *
   * <p>One {@code findAndModify} on {@code users} where {@code _id = userId, role = WORKER, shopId
   * = null}; two approvals racing for the same account cannot both win. Idempotent: an account that
   * already holds this very shop answers true without a write.
   *
   * @return true when the account's shop is {@code shopId} after the call; false when the account
   *     is not a WORKER account, does not exist, or another shop holds it (the caller answers {@code
   *     HR_WORKER_LOGIN_IN_OTHER_SHOP})
   */
  public boolean claimShop(String userId, String shopId) {
    if (userId == null || shopId == null) {
      return false;
    }
    Query free = Query.query(Criteria.where("_id").is(userId)
        .and("role").is(UserRole.WORKER.name())
        .and("shopId").is(null));
    Update claim = new Update().set("shopId", shopId).set("updatedAt", Instant.now());
    UserAccount claimed = mongoTemplate.findAndModify(
        free, claim, FindAndModifyOptions.options().returnNew(true), UserAccount.class);
    evict(userId);
    if (claimed != null) {
      log.info("Worker {} claimed by shop {}", userId, shopId);
      return true;
    }
    return userAccountRepository.findById(userId)
        .filter(a -> a.getRole() != null && a.getRole().isWorkerRole())
        .map(a -> shopId.equals(a.getShopId()))
        .orElse(false);
  }

  /**
   * Frees a WORKER account from {@code shopId} (R5.2, R5.3): clears {@code UserAccount.shopId} when
   * it names this shop, and ends the account's WORKER membership in the shop ({@code active =
   * false, endedAt = now}). Conditional on both sides, so calling it twice, or for a shop the
   * account never held, changes nothing. A staff account (OWNER, ADMIN, MANAGER, CASHIER) is left
   * alone: its staff membership is not a worker's membership (R13.4).
   *
   * @return true when the account's shop was cleared by this call
   */
  public boolean releaseShop(String userId, String shopId) {
    if (userId == null || shopId == null) {
      return false;
    }
    Query held = Query.query(Criteria.where("_id").is(userId)
        .and("role").is(UserRole.WORKER.name())
        .and("shopId").is(shopId));
    Update release = new Update().unset("shopId").set("updatedAt", Instant.now());
    UpdateResult result = mongoTemplate.updateFirst(held, release, UserAccount.class);
    boolean released = result.getModifiedCount() > 0;

    Instant now = Instant.now();
    membershipRepository.findByUserIdAndShopIdAndActiveTrue(userId, shopId)
        .filter(m -> m.getRole() != null && m.getRole().isWorkerRole())
        .ifPresent(m -> {
          m.setActive(false);
          m.setEndedAt(now);
          membershipRepository.save(m);
        });
    evict(userId);
    if (released) {
      log.info("Worker {} released from shop {}", userId, shopId);
    }
    return released;
  }

  /**
   * The account's own name and phone (R8.2), under the sign-up rules (R1.2): name 2–100 characters
   * after trimming, phone a 10-digit Indian mobile ({@code +91}, spaces and hyphens stripped). A
   * {@code name} that is null or blank means "unchanged" (the phone step of R1.11 sends the phone
   * alone, DD20); the phone is always required, and a blank or invalid one refuses the whole update
   * before anything is read. The e-mail cannot be changed here. Works for every role and every shop
   * state; the linked employee, when one exists, is HR's to update.
   *
   * @return the account as saved
   */
  public UserAccount updateProfile(String userId, String name, String phone) {
    if (phone == null || phone.isBlank()) {
      throw new ValidationException("Phone is required");
    }
    String cleanPhone = AuthValidator.normalizeIndianMobile(phone);
    boolean keepName = name == null || name.isBlank();
    String cleanName = keepName ? null : AuthValidator.validateName(name);
    try {
      UserAccount account = userAccountRepository.findById(userId)
          .orElseThrow(() -> new ResourceNotFoundException("User", "userId", userId));
      if (!keepName) {
        account.setName(cleanName);
      }
      account.setPhone(cleanPhone);
      account.setUpdatedAt(Instant.now());
      UserAccount saved = userAccountRepository.save(account);
      evict(userId);
      return saved;
    } catch (DataAccessException e) {
      log.error("Database error while updating profile of {}: {}", userId, e.getMessage(), e);
      throw new BaseException(ErrorCode.INTERNAL_SERVER_ERROR, "Error updating profile");
    }
  }

  /**
   * The shop's WORKER members for the Users page's "Workers" group (R2.5): name, e-mail, active
   * flag and the linked employee's code. Not staff seats.
   */
  public List<UserDto> workersOf(String shopId) {
    List<UserDto> workers = new ArrayList<>();
    if (shopId == null) {
      return workers;
    }
    for (UserShopMembership m : membershipRepository.findByShopIdAndActiveTrue(shopId)) {
      if (m.getRole() == null || !m.getRole().isWorkerRole()) {
        continue;
      }
      Optional<UserAccount> account = userAccountRepository.findById(m.getUserId());
      if (account.isEmpty()) {
        continue;
      }
      UserDto row = userMapper.toUserDtoWithRole(account.get(), UserRole.WORKER);
      row.setLinkedEmployeeCode(linkedEmployeeCode(shopId, m.getUserId()));
      workers.add(row);
    }
    return workers;
  }

  /**
   * The code of the shop's employee linked to this login, through {@link HrLinkedEmployeePort};
   * null when none, or when HR has not wired the port yet.
   */
  public String linkedEmployeeCode(String shopId, String userId) {
    if (hrLinkedEmployeePort == null || shopId == null || userId == null) {
      return null;
    }
    try {
      return hrLinkedEmployeePort.linkedEmployeeCode(shopId, userId);
    } catch (RuntimeException e) {
      // a missing code must not break the Users page
      log.warn("Linked employee code lookup failed for {} in {}: {}", userId, shopId, e.getMessage());
      return null;
    }
  }

  private void evict(String userId) {
    if (authCache != null) {
      authCache.evictUser(userId);
    }
  }
}
