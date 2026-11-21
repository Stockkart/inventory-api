package com.inventory.app.interceptor;

import com.inventory.plan.api.PlanStatus;
import com.inventory.accounting.api.AccountingFacade;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.inventory.app.config.CorsConfig;
import com.inventory.common.advice.GlobalExceptionHandler;
import com.inventory.common.constants.ErrorCode;
import com.inventory.common.dto.response.ApiErrorMapperImpl;
import com.inventory.common.exception.AuthenticationException;
import com.inventory.metrics.MetricsWrapper;
import com.inventory.product.domain.model.Shop;
import com.inventory.product.domain.repository.ShopRepository;
import com.inventory.product.mapper.ShopMapper;
import com.inventory.product.rest.controller.ShopController;
import com.inventory.product.rest.dto.response.ShopApprovalResponse;
import com.inventory.product.service.ShopService;
import com.inventory.product.service.vertical.ShopCapabilityService;
import com.inventory.product.service.vertical.ShopMenuService;
import com.inventory.product.service.vertical.ShopSellCatalogService;
import com.inventory.product.service.vertical.VerticalCatalogService;
import com.inventory.product.service.vertical.VerticalSchemaService;
import com.inventory.product.validation.ShopValidator;
import com.inventory.user.domain.model.UserAccount;
import com.inventory.user.domain.model.UserRole;
import com.inventory.user.domain.repository.UserAccountRepository;
import com.inventory.user.mapper.UserMapper;
import com.inventory.user.rest.controller.AuthController;
import com.inventory.user.service.AuthService;
import com.inventory.user.service.RbacService;
import com.inventory.user.service.TokenValidationService;
import com.inventory.user.service.UserShopMembershipService;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * HTTP boundary through the real interceptor chain ({@link CorsConfig}: authentication → RBAC →
 * plan expiry) and {@link GlobalExceptionHandler}. Only the token lookup and persistence are mocked;
 * the shop-approval role check runs in the real {@link ShopService} and {@link ShopValidator}.
 *
 * <p>The nested configuration replaces {@code AppApplication}, whose Mongo repositories and
 * {@code com.inventory.*} scan a web slice cannot start without a database.
 */
@WebMvcTest(properties = "client.url=http://localhost:3000")
class AuthenticationInterceptorBoundaryTest {

  private static final String SHOP_ID = "65f000000000000000000001";

  @Configuration
  @Import({
      CorsConfig.class,
      AuthenticationInterceptor.class,
      RbacModuleInterceptor.class,
      PlanExpiryInterceptor.class,
      GlobalExceptionHandler.class,
      ApiErrorMapperImpl.class,
      AuthController.class,
      ShopController.class,
      ShopService.class,
      ShopValidator.class,
      AttendanceAppProbeController.class
  })
  static class Slice {}

  /**
   * Stands in for {@code AttendanceAppController} and {@code AttendanceAppHealthController} (HR
   * module) so the test can tell whether a request got through the chain {@link CorsConfig}
   * registers, without wiring the HR services.
   */
  @RestController
  static class AttendanceAppProbeController {
    @PostMapping("/api/v1/hr/attendance-app/pairings/claim")
    String claim() {
      return "reached";
    }

    @GetMapping("/api/v1/hr/attendance-app/health")
    String health() {
      return "reached";
    }
  }

  @Autowired private MockMvc mvc;

  // authentication
  @MockBean private TokenValidationService tokenValidationService;
  @MockBean private UserShopMembershipService membershipService;
  @MockBean private RbacService rbacService;
  // AuthController
  @MockBean private AuthService authService;
  @MockBean private UserMapper userMapper;
  // ShopController and ShopService
  @MockBean private VerticalSchemaService verticalSchemaService;
  @MockBean private ShopCapabilityService shopCapabilityService;
  @MockBean private ShopMenuService shopMenuService;
  @MockBean private ShopSellCatalogService shopSellCatalogService;
  @MockBean private VerticalCatalogService verticalCatalogService;
  @MockBean private ShopRepository shopRepository;
  @MockBean private ShopMapper shopMapper;
  @MockBean private UserAccountRepository userAccountRepository;
  @MockBean private MetricsWrapper metrics;
  // ShopService seeds the chart of accounts through the accounting api on registration.
  @MockBean private AccountingFacade accountingFacade;
  // PlanExpiryInterceptor asks the plan api; an unexpired answer (the mock's false) lets requests through.
  @MockBean private PlanStatus planStatus;
  // CorsConfig also registers the Attendance app envelope interceptor on the device paths; the
  // mock answers true so a device request runs on to the probe controller
  @MockBean private com.inventory.hr.service.AttendanceAppAuthInterceptor attendanceAppAuthInterceptor;

  private static UserAccount account(String userId, UserRole role, String shopId) {
    UserAccount a = new UserAccount();
    a.setUserId(userId);
    a.setRole(role);
    a.setShopId(shopId);
    a.setActive(true);
    return a;
  }

  @BeforeEach
  void setUp() throws Exception {
    when(attendanceAppAuthInterceptor.preHandle(any(), any(), any())).thenReturn(true);
    // any other token is unknown, revoked or expired
    doThrow(new AuthenticationException(ErrorCode.INVALID_CREDENTIALS, "Invalid or expired token"))
        .when(tokenValidationService).validateToken(anyString());
    doReturn(account("owner-1", UserRole.OWNER, SHOP_ID))
        .when(tokenValidationService).validateToken("owner-token");
    doReturn(account("staff-1", UserRole.SUPER_USER, null))
        .when(tokenValidationService).validateToken("staff-token");

    Shop pending = new Shop();
    pending.setShopId(SHOP_ID);
    pending.setStatus("PENDING");
    when(shopRepository.findById(SHOP_ID)).thenReturn(Optional.of(pending));
    when(shopRepository.save(any(Shop.class))).thenAnswer(i -> i.getArgument(0));
    when(shopMapper.toApprovalResponse(any(Shop.class))).thenAnswer(i -> {
      Shop s = i.getArgument(0);
      return new ShopApprovalResponse(s.getShopId(), s.isActive(), s.getUserLimit(), s.getUserCount());
    });
  }

  @Test
  void changePasswordWithoutABearerTokenIs401() throws Exception {
    mvc.perform(post("/api/v1/auth/change-password")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"currentPassword\":\"old-password\",\"password\":\"brand-new-password\"}"))
        .andExpect(status().isUnauthorized());

    verify(authService, never()).changePassword(any(), any(), any());
  }

  @Test
  void changePasswordWithAnUnknownTokenIs401() throws Exception {
    mvc.perform(post("/api/v1/auth/change-password")
            .header("Authorization", "Bearer stolen-or-revoked")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"currentPassword\":\"old-password\",\"password\":\"brand-new-password\"}"))
        .andExpect(status().isUnauthorized());

    verify(authService, never()).changePassword(any(), any(), any());
  }

  @Test
  void approvingAShopAsAShopOwnerIs403() throws Exception {
    mvc.perform(post("/api/v1/shops/admin/shops/{shopId}/approve", SHOP_ID)
            .header("Authorization", "Bearer owner-token")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"approve\":true,\"userLimit\":5}"))
        .andExpect(status().isForbidden());

    verify(shopRepository, never()).save(any());
  }

  @Test
  void approvingAShopAsStockkartStaffSucceeds() throws Exception {
    mvc.perform(post("/api/v1/shops/admin/shops/{shopId}/approve", SHOP_ID)
            .header("Authorization", "Bearer staff-token")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"approve\":true,\"userLimit\":5}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.shopId").value(SHOP_ID))
        .andExpect(jsonPath("$.data.active").value(true))
        .andExpect(jsonPath("$.data.userLimit").value(5));

    verify(shopRepository).save(any(Shop.class));
  }

  /**
   * HR task 5.1 / 5.10: the Attendance app's device paths are excluded from the Bearer, RBAC and
   * plan-expiry interceptors and handed to the envelope interceptor, so a request with no
   * Authorization header at all reaches the controller.
   */
  @Test
  void anAttendanceAppDeviceRequestWithoutABearerReachesTheController() throws Exception {
    mvc.perform(post("/api/v1/hr/attendance-app/pairings/claim")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{}"))
        .andExpect(status().isOk())
        .andExpect(content().string("reached"));

    verify(attendanceAppAuthInterceptor).preHandle(any(), any(), any());
    verifyNoInteractions(tokenValidationService, rbacService, planStatus);
  }

  /** The manager's health read is not a device path: Bearer first, the envelope never. */
  @Test
  void theAttendanceAppHealthReadStillNeedsABearer() throws Exception {
    mvc.perform(get("/api/v1/hr/attendance-app/health"))
        .andExpect(status().isUnauthorized());
    verify(attendanceAppAuthInterceptor, never()).preHandle(any(), any(), any());

    mvc.perform(get("/api/v1/hr/attendance-app/health")
            .header("Authorization", "Bearer owner-token"))
        .andExpect(status().isOk())
        .andExpect(content().string("reached"));
    verify(attendanceAppAuthInterceptor, never()).preHandle(any(), any(), any());
    verify(tokenValidationService).validateToken("owner-token");
    verify(rbacService).requireModule(any(), any(), any());
  }
}
