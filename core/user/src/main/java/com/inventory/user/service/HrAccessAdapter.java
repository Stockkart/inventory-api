package com.inventory.user.service;

import com.inventory.user.rest.dto.response.ShopAccessResponse;

/**
 * Port to the HR module (implemented by {@code core/hr} as {@code HrAccessAdapterImpl}), so the
 * user module can add the HR block to {@code /shops/me/access} without depending on HR.
 */
public interface HrAccessAdapter {

  /**
   * HR block for the caller.
   *
   * @param userId the caller
   * @param shopId the shop from the request attribute
   * @param base access already resolved by {@link RbacService} (owner flag, role and modules); the
   *     implementation must not call back into {@link RbacService}
   * @return effective HR permissions and the linked employee id, never {@code null}
   */
  ShopAccessResponse.HrAccessDto resolve(String userId, String shopId, ShopAccessResponse base);
}
