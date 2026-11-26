package com.inventory.user.service.api;

import com.inventory.user.api.WorkerAccounts;
import com.inventory.user.domain.model.UserAccount;
import com.inventory.user.domain.model.UserRole;
import com.inventory.user.domain.repository.UserAccountRepository;
import com.inventory.user.rest.dto.response.OwnerShopDto;
import com.inventory.user.rest.dto.response.OwnerShopListResponse;
import com.inventory.user.service.JoinRequestService;
import com.inventory.user.service.UserPhotoService;
import com.inventory.user.service.UserShopMembershipService;
import com.inventory.user.service.WorkerAccountService;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * {@link WorkerAccounts} over the account, membership, photo and staff join-request services. The
 * owner-e-mail lookup reuses {@link JoinRequestService#getShopsByOwnerEmail} (the staff flow's
 * lookup, design DD2) and adds the owner's display name.
 */
@Service
public class WorkerAccountsImpl implements WorkerAccounts {

  private final UserAccountRepository userAccountRepository;
  private final WorkerAccountService workerAccountService;
  private final UserShopMembershipService membershipService;
  private final UserPhotoService userPhotoService;
  private final JoinRequestService joinRequestService;

  public WorkerAccountsImpl(UserAccountRepository userAccountRepository,
      WorkerAccountService workerAccountService, UserShopMembershipService membershipService,
      UserPhotoService userPhotoService, JoinRequestService joinRequestService) {
    this.userAccountRepository = userAccountRepository;
    this.workerAccountService = workerAccountService;
    this.membershipService = membershipService;
    this.userPhotoService = userPhotoService;
    this.joinRequestService = joinRequestService;
  }

  @Override
  public Optional<AccountView> account(String userId) {
    if (userId == null || userId.isBlank()) {
      return Optional.empty();
    }
    return userAccountRepository.findById(userId).map(WorkerAccountsImpl::view);
  }

  @Override
  public List<OwnedShop> shopsOwnedBy(String ownerEmail) {
    String email = ownerEmail != null ? ownerEmail.trim().toLowerCase(Locale.ROOT) : "";
    if (email.isEmpty()) {
      return List.of();
    }
    OwnerShopListResponse owned = joinRequestService.getShopsByOwnerEmail(email);
    if (owned == null || owned.getData() == null || owned.getData().isEmpty()) {
      return List.of();
    }
    String ownerName = userAccountRepository.findByEmail(email)
        .map(UserAccount::getName)
        .orElse(null);
    List<OwnedShop> shops = new ArrayList<>(owned.getData().size());
    for (OwnerShopDto shop : owned.getData()) {
      shops.add(new OwnedShop(shop.getShopId(), shop.getShopName(), ownerName));
    }
    return shops;
  }

  @Override
  public boolean claimShop(String shopId, String userId) {
    return workerAccountService.claimShop(userId, shopId);
  }

  @Override
  public boolean releaseShop(String shopId, String userId) {
    return workerAccountService.releaseShop(userId, shopId);
  }

  @Override
  public void addWorkerMembership(String shopId, String userId) {
    membershipService.addMembership(userId, shopId, UserRole.WORKER,
        UserShopMembershipService.RELATIONSHIP_INVITED);
  }

  @Override
  public boolean hasPhoto(String userId) {
    return userPhotoService.hasPhoto(userId);
  }

  @Override
  public AccountView updateProfile(String userId, String name, String phone) {
    return view(workerAccountService.updateProfile(userId, name, phone));
  }

  static AccountView view(UserAccount a) {
    UserRole role = a.getRole();
    return new AccountView(a.getUserId(), a.getName(), a.getEmail(), a.getPhone(),
        role != null ? role.name() : null, blankToNull(a.getShopId()),
        role != null && role.isWorkerRole());
  }

  private static String blankToNull(String s) {
    return s == null || s.isBlank() ? null : s;
  }
}
