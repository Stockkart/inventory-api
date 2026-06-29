package com.inventory.user.rest.controller;

import com.inventory.common.constants.ErrorCode;
import com.inventory.common.dto.response.ApiResponse;
import com.inventory.common.exception.AuthenticationException;
import com.inventory.user.domain.model.ShopRbacPolicyDocument;
import com.inventory.user.rest.dto.request.UpdateMemberPermissionsRequest;
import com.inventory.user.rest.dto.request.UpdateShopRbacPolicyRequest;
import com.inventory.user.rest.dto.response.ShopAccessResponse;
import com.inventory.user.rest.dto.response.ShopMemberAccessDto;
import com.inventory.user.rest.dto.response.ShopRbacAdminResponse;
import com.inventory.user.service.RbacService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/shops")
public class ShopRbacController {

  @Autowired private RbacService rbacService;

  @GetMapping("/me/access")
  public ResponseEntity<ApiResponse<ShopAccessResponse>> getMyAccess(
      HttpServletRequest httpRequest) {
    String userId = requireUserId(httpRequest);
    String shopId = requireShopId(httpRequest);
    return ResponseEntity.ok(ApiResponse.success(rbacService.getEffectiveAccess(userId, shopId)));
  }

  @GetMapping("/{shopId}/rbac")
  public ResponseEntity<ApiResponse<ShopRbacAdminResponse>> getShopRbac(
      @PathVariable String shopId, HttpServletRequest httpRequest) {
    String userId = requireUserId(httpRequest);
    return ResponseEntity.ok(ApiResponse.success(rbacService.getAdminView(userId, shopId)));
  }

  @PatchMapping("/{shopId}/rbac/policy")
  public ResponseEntity<ApiResponse<ShopRbacPolicyDocument>> updateShopRbacPolicy(
      @PathVariable String shopId,
      @RequestBody UpdateShopRbacPolicyRequest request,
      HttpServletRequest httpRequest) {
    String userId = requireUserId(httpRequest);
    return ResponseEntity.ok(
        ApiResponse.success(rbacService.updateShopPolicy(userId, shopId, request)));
  }

  @PatchMapping("/{shopId}/rbac/members/{memberUserId}")
  public ResponseEntity<ApiResponse<ShopMemberAccessDto>> updateMemberPermissions(
      @PathVariable String shopId,
      @PathVariable String memberUserId,
      @RequestBody UpdateMemberPermissionsRequest request,
      HttpServletRequest httpRequest) {
    String userId = requireUserId(httpRequest);
    return ResponseEntity.ok(
        ApiResponse.success(
            rbacService.updateMemberPermissions(userId, shopId, memberUserId, request)));
  }

  private static String requireUserId(HttpServletRequest request) {
    String userId = (String) request.getAttribute("userId");
    if (!StringUtils.hasText(userId)) {
      throw new AuthenticationException(ErrorCode.UNAUTHORIZED, "User not authenticated");
    }
    return userId;
  }

  private static String requireShopId(HttpServletRequest request) {
    String shopId = (String) request.getAttribute("shopId");
    if (!StringUtils.hasText(shopId)) {
      throw new AuthenticationException(ErrorCode.UNAUTHORIZED, "Shop context is required");
    }
    return shopId;
  }
}
