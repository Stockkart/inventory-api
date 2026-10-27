package com.inventory.user.rest.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.inventory.common.dto.response.ApiResponse;
import com.inventory.user.domain.model.UserAccount;
import com.inventory.user.domain.model.UserRole;
import com.inventory.user.mapper.UserMapper;
import com.inventory.user.rest.dto.response.UserResponse;
import com.inventory.user.rest.dto.response.UserShopListResponse;
import com.inventory.user.service.AuthService;
import com.inventory.user.service.UserShopMembershipService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mapstruct.factory.Mappers;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

/** A4/A5: GET /api/v1/auth/me for a Stockkart staff account (SUPER_USER, no shop). */
@ExtendWith(MockitoExtension.class)
class AuthControllerMeSuperUserTest {

  @Mock private AuthService authService;
  @Mock private UserShopMembershipService membershipService;
  @Spy private UserMapper userMapper = Mappers.getMapper(UserMapper.class);
  @InjectMocks private AuthController authController;

  @Test
  void meReturnsSuperUserRoleNoShopAndNoShops() throws Exception {
    UserAccount account = new UserAccount();
    account.setUserId("staff-1");
    account.setEmail("staff@stockkart.example");
    account.setName("Staff One");
    account.setRole(UserRole.SUPER_USER);
    account.setActive(true);
    when(membershipService.getShopsForUser("staff-1")).thenReturn(new UserShopListResponse(List.of()));
    // AuthenticationInterceptor puts the validated account here
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/auth/me");
    request.setAttribute("userAccount", account);

    ResponseEntity<ApiResponse<UserResponse>> response = authController.getCurrentUser(request);

    UserResponse body = response.getBody().getData();
    assertEquals(UserRole.SUPER_USER, body.getRole());
    assertNull(body.getShopId());
    assertTrue(body.getShops().isEmpty());
    String json = new ObjectMapper().findAndRegisterModules().writeValueAsString(response.getBody());
    assertTrue(json.contains("\"role\":\"SUPER_USER\""), json);
  }
}
