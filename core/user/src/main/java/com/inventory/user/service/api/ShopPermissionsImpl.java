package com.inventory.user.service.api;

import com.inventory.user.api.ShopPermissions;
import com.inventory.user.service.RbacService;
import com.inventory.user.service.UserShopMembershipService;
import org.springframework.stereotype.Service;

/** {@link ShopPermissions} over the membership and RBAC services. */
@Service
public class ShopPermissionsImpl implements ShopPermissions {

  private final UserShopMembershipService membershipService;
  private final RbacService rbacService;

  public ShopPermissionsImpl(UserShopMembershipService membershipService, RbacService rbacService) {
    this.membershipService = membershipService;
    this.rbacService = rbacService;
  }

  @Override
  public boolean hasAccess(String shopId, String userId) {
    return membershipService.hasAccess(userId, shopId);
  }

  @Override
  public boolean canEditRates(String shopId, String userId) {
    return rbacService.canEditRates(userId, shopId);
  }
}
