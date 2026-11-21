package com.inventory.hr.adapter;

import com.inventory.hr.domain.model.enums.HrPermission;
import com.inventory.hr.service.HrAccessService;
import com.inventory.user.rest.dto.response.ShopAccessResponse;
import com.inventory.user.service.HrAccessAdapter;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * HR block of {@code GET /shops/me/access}: effective permissions (enum order) and the caller's
 * linked employee. Works from the access {@code RbacService} already resolved, so it never calls
 * back into it.
 */
@Component
public class HrAccessAdapterImpl implements HrAccessAdapter {

  @Autowired private HrAccessService hrAccessService;

  @Override
  public ShopAccessResponse.HrAccessDto resolve(
      String userId, String shopId, ShopAccessResponse base) {
    List<String> permissions = hrAccessService.permissionsFor(base, shopId).stream()
        .map(HrPermission::name)
        .toList();
    return ShopAccessResponse.HrAccessDto.builder()
        .permissions(permissions)
        .employeeId(hrAccessService.linkedEmployeeId(userId, shopId))
        .build();
  }
}
