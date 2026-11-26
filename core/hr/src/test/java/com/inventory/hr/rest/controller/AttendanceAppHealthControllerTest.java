package com.inventory.hr.rest.controller;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.inventory.common.advice.GlobalExceptionHandler;
import com.inventory.common.constants.ErrorCode;
import com.inventory.common.dto.response.ApiErrorMapperImpl;
import com.inventory.common.exception.BaseException;
import com.inventory.hr.rest.dto.response.AppHealthResponse;
import com.inventory.hr.service.AppPunchHealthService;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * {@code GET /api/v1/hr/attendance-app/health} through MVC (R10.5): the response shape the HR
 * dashboard panel reads, 403 when the service refuses {@code VIEW_ATTENDANCE}, 401 without the
 * Bearer attributes (the path is on the Bearer chain, never the device chain).
 */
@ExtendWith(MockitoExtension.class)
class AttendanceAppHealthControllerTest {

  private static final String PATH = "/api/v1/hr/attendance-app/health";
  private static final String USER = "u1";
  private static final String SHOP = "65f000000000000000000b01";

  @Mock private AppPunchHealthService healthService;

  private MockMvc mvc;

  @BeforeEach
  void setUp() {
    AttendanceAppHealthController controller = new AttendanceAppHealthController();
    ReflectionTestUtils.setField(controller, "healthService", healthService);
    GlobalExceptionHandler handler = new GlobalExceptionHandler();
    ReflectionTestUtils.setField(handler, "apiErrorMapper", new ApiErrorMapperImpl());
    mvc = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(handler).build();
  }

  @Test
  void returnsTheOpenAlertsOfTheCallersShop() throws Exception {
    Instant raisedAt = Instant.parse("2025-04-03T09:12:00Z");
    when(healthService.openAlerts(USER, SHOP)).thenReturn(new AppHealthResponse(
        List.of(new AppHealthResponse.Alert(SHOP, 0.05, 1, 20, raisedAt))));

    mvc.perform(get(PATH).requestAttr("userId", USER).requestAttr("shopId", SHOP))
        .andExpect(status().isOk())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.data.alerts.length()").value(1))
        .andExpect(jsonPath("$.data.alerts[0].shopId").value(SHOP))
        .andExpect(jsonPath("$.data.alerts[0].rate").value(0.05))
        .andExpect(jsonPath("$.data.alerts[0].failed").value(1))
        .andExpect(jsonPath("$.data.alerts[0].attempts").value(20))
        // the standalone MockMvc has no Boot Jackson config; production writes the ISO instant
        .andExpect(jsonPath("$.data.alerts[0].raisedAt").exists());
  }

  @Test
  void emptyListWhenNothingIsOpen() throws Exception {
    when(healthService.openAlerts(USER, SHOP)).thenReturn(new AppHealthResponse(List.of()));

    mvc.perform(get(PATH).requestAttr("userId", USER).requestAttr("shopId", SHOP))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.alerts").isArray())
        .andExpect(jsonPath("$.data.alerts.length()").value(0));
  }

  @Test
  void forbiddenWithoutViewAttendance() throws Exception {
    when(healthService.openAlerts(USER, SHOP))
        .thenThrow(new BaseException(ErrorCode.ACCESS_DENIED, "You do not have permission"));

    mvc.perform(get(PATH).requestAttr("userId", USER).requestAttr("shopId", SHOP))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.success").value(false))
        .andExpect(jsonPath("$.error").value("You do not have permission"));
  }

  @Test
  void unauthorizedWithoutTheBearerAttributes() throws Exception {
    mvc.perform(get(PATH)).andExpect(status().isUnauthorized());
    mvc.perform(get(PATH).requestAttr("userId", USER)).andExpect(status().isUnauthorized());
    verify(healthService, never()).openAlerts(anyString(), anyString());
  }
}
