package com.inventory.hr.rest.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.inventory.common.advice.GlobalExceptionHandler;
import com.inventory.common.constants.ErrorCode;
import com.inventory.common.dto.response.ApiErrorMapperImpl;
import com.inventory.common.exception.BaseException;
import com.inventory.common.exception.ResourceNotFoundException;
import com.inventory.common.exception.ValidationException;
import com.inventory.hr.domain.model.enums.EmployeeStatus;
import com.inventory.hr.domain.model.enums.JoinApproveMode;
import com.inventory.hr.domain.model.enums.JoinOutcome;
import com.inventory.hr.domain.model.enums.WorkerJoinStatus;
import com.inventory.hr.rest.dto.response.BulkJoinResult;
import com.inventory.hr.rest.dto.response.JoinRequestDecision;
import com.inventory.hr.rest.dto.response.JoinRequestDetail;
import com.inventory.hr.rest.dto.response.JoinRequestRow;
import com.inventory.hr.rest.dto.response.JoinRequestsPageResponse;
import com.inventory.hr.service.WorkerJoinService;
import com.inventory.hr.validation.JoinRequestInboxValidator;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * The Join requests inbox through MVC (worker-app R4.1–R4.7, R4.10, R11.3): request shapes, the
 * {@code ApiResponse} envelope, 400s from the validator before the service is reached, and the
 * service's refusals (403 from the guard, 409 / 422 business codes, 404) passing through with
 * their code and text. The interceptor chain is not part of a standalone MockMvc, so the caller
 * and shop the {@code AuthenticationInterceptor} would have set are request attributes here; the
 * {@code ADD_EDIT_EMPLOYEES} check itself lives in {@link WorkerJoinService} and is a mock.
 */
@ExtendWith(MockitoExtension.class)
class JoinRequestInboxControllerTest {

  private static final String BASE = "/api/v1/hr/join-requests";
  private static final String USER_ID = "66f1a2b3c4d5e6f7a8b9c0a1";
  private static final String SHOP_ID = "66f1a2b3c4d5e6f7a8b9c0d2";
  private static final String REQUEST_ID = "66f1a2b3c4d5e6f7a8b9c0e1";
  private static final String OTHER_REQUEST_ID = "66f1a2b3c4d5e6f7a8b9c0e2";
  private static final String EMPLOYEE_ID = "66f1a2b3c4d5e6f7a8b9c0d3";
  private static final Instant REQUESTED_AT = Instant.parse("2025-05-12T09:12:00Z");
  /** {@code HrPermissionGuard.DENIED}, as the service throws it. */
  private static final String DENIED_TEXT = "You do not have permission to do this in HR";

  @Mock private WorkerJoinService joinService;

  private MockMvc mvc;

  @BeforeEach
  void setUp() {
    JoinRequestInboxController controller = new JoinRequestInboxController();
    ReflectionTestUtils.setField(controller, "joinService", joinService);
    ReflectionTestUtils.setField(controller, "validator", new JoinRequestInboxValidator());
    GlobalExceptionHandler handler = new GlobalExceptionHandler();
    ReflectionTestUtils.setField(handler, "apiErrorMapper", new ApiErrorMapperImpl());
    mvc = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(handler).build();
  }

  /** What {@code AuthenticationInterceptor} leaves on a Bearer request. */
  private static MockHttpServletRequestBuilder asManager(MockHttpServletRequestBuilder request) {
    return request.requestAttr("userId", USER_ID).requestAttr("shopId", SHOP_ID);
  }

  private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request,
      String body) {
    return asManager(request).contentType(MediaType.APPLICATION_JSON).content(body);
  }

  private static JoinRequestRow row(String id) {
    JoinRequestRow row = new JoinRequestRow();
    row.setId(id);
    row.setName("Priya Sharma");
    row.setPhone("9876543210");
    row.setEmail("priya@example.com");
    row.setPhotoUrl("/api/v1/users/" + USER_ID + "/photo");
    row.setRequestedAt(REQUESTED_AT);
    row.setStatus(WorkerJoinStatus.PENDING);
    row.setHasPossibleMatch(false);
    return row;
  }

  private static BaseException denied() {
    return new BaseException(ErrorCode.ACCESS_DENIED, DENIED_TEXT);
  }

  // ---- list ------------------------------------------------------------------------------------

  @Test
  void listDefaultsToPendingAndReturnsRowsPendingCountAndPage() throws Exception {
    when(joinService.inbox(USER_ID, SHOP_ID, "PENDING", null, null))
        .thenReturn(new JoinRequestsPageResponse(List.of(row(REQUEST_ID)), 3, 0, 50, 3, 1));

    mvc.perform(asManager(get(BASE)))
        .andExpect(status().isOk())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.data.items.length()").value(1))
        .andExpect(jsonPath("$.data.items[0].id").value(REQUEST_ID))
        .andExpect(jsonPath("$.data.items[0].kind").doesNotExist())
        .andExpect(jsonPath("$.data.items[0].pairedEmployee").doesNotExist())
        .andExpect(jsonPath("$.data.items[0].name").value("Priya Sharma"))
        .andExpect(jsonPath("$.data.items[0].phone").value("9876543210"))
        .andExpect(jsonPath("$.data.items[0].email").value("priya@example.com"))
        .andExpect(jsonPath("$.data.items[0].photoUrl").value("/api/v1/users/" + USER_ID + "/photo"))
        .andExpect(jsonPath("$.data.items[0].status").value("PENDING"))
        .andExpect(jsonPath("$.data.items[0].hasPossibleMatch").value(false))
        .andExpect(jsonPath("$.data.items[0].userId").doesNotExist())
        .andExpect(jsonPath("$.data.pendingCount").value(3))
        .andExpect(jsonPath("$.data.page").value(0))
        .andExpect(jsonPath("$.data.size").value(50))
        .andExpect(jsonPath("$.data.totalItems").value(3))
        .andExpect(jsonPath("$.data.totalPages").value(1));
  }

  @Test
  void listPassesStatusPageAndSizeThrough() throws Exception {
    when(joinService.inbox(USER_ID, SHOP_ID, "REJECTED", 2, 200))
        .thenReturn(new JoinRequestsPageResponse(List.of(), 0, 2, 200, 0, 0));

    mvc.perform(asManager(get(BASE).param("status", "rejected").param("page", "2")
            .param("size", "200")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.items.length()").value(0))
        .andExpect(jsonPath("$.data.pendingCount").value(0));
  }

  @Test
  void listRefusesAnUnknownStatusBeforeTheService() throws Exception {
    mvc.perform(asManager(get(BASE).param("status", "APPROVED")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.success").value(false))
        .andExpect(jsonPath("$.error").value("status must be PENDING or REJECTED"))
        .andExpect(jsonPath("$.data.status").value(400));
    verifyNoInteractions(joinService);
  }

  @Test
  void listRefusesAPageOver200BeforeTheService() throws Exception {
    mvc.perform(asManager(get(BASE).param("size", "201")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("size must be between 1 and 200"));
    mvc.perform(asManager(get(BASE).param("page", "-1")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("page must be 0 or more"));
    verifyNoInteractions(joinService);
  }

  @Test
  void listWithoutACallerIs401() throws Exception {
    mvc.perform(get(BASE).requestAttr("shopId", SHOP_ID))
        .andExpect(status().isUnauthorized());
    verifyNoInteractions(joinService);
  }

  @Test
  void listWithoutAShopIs401() throws Exception {
    mvc.perform(get(BASE).requestAttr("userId", USER_ID))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.error").value("Shop context is required"));
    verifyNoInteractions(joinService);
  }

  @Test
  void aCallerWithoutAddEditEmployeesGets403FromTheService() throws Exception {
    when(joinService.inbox(USER_ID, SHOP_ID, "PENDING", null, null)).thenThrow(denied());

    mvc.perform(asManager(get(BASE)))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.success").value(false))
        .andExpect(jsonPath("$.error").value(DENIED_TEXT))
        .andExpect(jsonPath("$.data.status").value(403));
  }

  // ---- detail ----------------------------------------------------------------------------------

  @Test
  void detailReturnsTheRowAndThePossibleMatch() throws Exception {
    JoinRequestDetail detail = new JoinRequestDetail();
    JoinRequestRow base = row(REQUEST_ID);
    detail.setId(base.getId());
    detail.setName(base.getName());
    detail.setPhone(base.getPhone());
    detail.setEmail(base.getEmail());
    detail.setRequestedAt(base.getRequestedAt());
    detail.setStatus(base.getStatus());
    detail.setHasPossibleMatch(true);
    detail.setPossibleMatch(new JoinRequestDetail.PossibleMatch(EMPLOYEE_ID, "EMP-014",
        "Ravi Kumar", EmployeeStatus.ACTIVE, false));
    when(joinService.detail(USER_ID, SHOP_ID, REQUEST_ID)).thenReturn(detail);

    mvc.perform(asManager(get(BASE + "/" + REQUEST_ID)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.id").value(REQUEST_ID))
        .andExpect(jsonPath("$.data.hasPossibleMatch").value(true))
        .andExpect(jsonPath("$.data.possibleMatch.employeeId").value(EMPLOYEE_ID))
        .andExpect(jsonPath("$.data.possibleMatch.code").value("EMP-014"))
        .andExpect(jsonPath("$.data.possibleMatch.name").value("Ravi Kumar"))
        .andExpect(jsonPath("$.data.possibleMatch.status").value("ACTIVE"))
        .andExpect(jsonPath("$.data.possibleMatch.linked").value(false));
  }

  @Test
  void detailRefusesAMalformedIdBeforeTheService() throws Exception {
    mvc.perform(asManager(get(BASE + "/req-1")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("id must be a 24-character id"));
    verifyNoInteractions(joinService);
  }

  @Test
  void detailOfAnotherShopsRequestIs404() throws Exception {
    when(joinService.detail(USER_ID, SHOP_ID, REQUEST_ID))
        .thenThrow(new ResourceNotFoundException("Join request", "id", REQUEST_ID));

    mvc.perform(asManager(get(BASE + "/" + REQUEST_ID)))
        .andExpect(status().isNotFound());
  }

  @Test
  void detailWithoutPermissionIs403() throws Exception {
    when(joinService.detail(USER_ID, SHOP_ID, REQUEST_ID)).thenThrow(denied());

    mvc.perform(asManager(get(BASE + "/" + REQUEST_ID)))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.error").value(DENIED_TEXT));
  }

  // ---- approve ---------------------------------------------------------------------------------

  @Test
  void approveCreateAnswersTheDecision() throws Exception {
    when(joinService.approve(USER_ID, SHOP_ID, REQUEST_ID, JoinApproveMode.CREATE, null))
        .thenReturn(new JoinRequestDecision(REQUEST_ID, JoinOutcome.APPROVED_CREATED,
            EMPLOYEE_ID));

    mvc.perform(json(post(BASE + "/" + REQUEST_ID + "/approve"), "{\"mode\":\"CREATE\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.data.id").value(REQUEST_ID))
        .andExpect(jsonPath("$.data.outcome").value("APPROVED_CREATED"))
        .andExpect(jsonPath("$.data.employeeId").value(EMPLOYEE_ID));
  }

  @Test
  void approveLinkPassesTheEmployeeIdAndIsCaseInsensitiveOnMode() throws Exception {
    when(joinService.approve(USER_ID, SHOP_ID, REQUEST_ID, JoinApproveMode.LINK, EMPLOYEE_ID))
        .thenReturn(new JoinRequestDecision(REQUEST_ID, JoinOutcome.APPROVED_LINKED,
            EMPLOYEE_ID));

    mvc.perform(json(post(BASE + "/" + REQUEST_ID + "/approve"),
            "{\"mode\":\"link\",\"employeeId\":\" " + EMPLOYEE_ID + " \"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.outcome").value("APPROVED_LINKED"))
        .andExpect(jsonPath("$.data.employeeId").value(EMPLOYEE_ID));
  }

  @Test
  void approveCreateIgnoresAnEmployeeId() throws Exception {
    when(joinService.approve(USER_ID, SHOP_ID, REQUEST_ID, JoinApproveMode.CREATE, null))
        .thenReturn(new JoinRequestDecision(REQUEST_ID, JoinOutcome.APPROVED_CREATED,
            EMPLOYEE_ID));

    mvc.perform(json(post(BASE + "/" + REQUEST_ID + "/approve"),
            "{\"mode\":\"CREATE\",\"employeeId\":\"whatever\"}"))
        .andExpect(status().isOk());
    verify(joinService).approve(USER_ID, SHOP_ID, REQUEST_ID, JoinApproveMode.CREATE, null);
  }

  @Test
  void approveRefusesAnUnknownModeBeforeTheService() throws Exception {
    mvc.perform(json(post(BASE + "/" + REQUEST_ID + "/approve"), "{\"mode\":\"MERGE\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("mode must be CREATE or LINK"));
    mvc.perform(json(post(BASE + "/" + REQUEST_ID + "/approve"), "{}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("mode must be CREATE or LINK"));
    verifyNoInteractions(joinService);
  }

  @Test
  void approveLinkWithoutAnEmployeeIdIs400() throws Exception {
    mvc.perform(json(post(BASE + "/" + REQUEST_ID + "/approve"), "{\"mode\":\"LINK\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("employeeId is required to link"));
    mvc.perform(json(post(BASE + "/" + REQUEST_ID + "/approve"),
            "{\"mode\":\"LINK\",\"employeeId\":\"EMP-014\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("employeeId must be a 24-character id"));
    verifyNoInteractions(joinService);
  }

  @Test
  void approveRefusesAMalformedRequestIdBeforeTheService() throws Exception {
    mvc.perform(json(post(BASE + "/not-an-id/approve"), "{\"mode\":\"CREATE\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("id must be a 24-character id"));
    verifyNoInteractions(joinService);
  }

  @Test
  void approveWithoutABodyIsMalformedJson() throws Exception {
    mvc.perform(asManager(post(BASE + "/" + REQUEST_ID + "/approve"))
            .contentType(MediaType.APPLICATION_JSON))
        .andExpect(status().isBadRequest());
    verifyNoInteractions(joinService);
  }

  @Test
  void approveWithoutPermissionIs403() throws Exception {
    when(joinService.approve(USER_ID, SHOP_ID, REQUEST_ID, JoinApproveMode.CREATE, null))
        .thenThrow(denied());

    mvc.perform(json(post(BASE + "/" + REQUEST_ID + "/approve"), "{\"mode\":\"CREATE\"}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.error").value(DENIED_TEXT))
        .andExpect(jsonPath("$.data.status").value(403));
  }

  @Test
  void anAlreadyHandledRequestIs409WithTheTextWordForWord() throws Exception {
    when(joinService.approve(USER_ID, SHOP_ID, REQUEST_ID, JoinApproveMode.CREATE, null))
        .thenThrow(new BaseException(ErrorCode.HR_REQUEST_ALREADY_HANDLED,
            ErrorCode.HR_REQUEST_ALREADY_HANDLED.getMessage()));

    mvc.perform(json(post(BASE + "/" + REQUEST_ID + "/approve"), "{\"mode\":\"CREATE\"}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error").value("This request was already handled"))
        .andExpect(jsonPath("$.data.message").value("This request was already handled"))
        .andExpect(jsonPath("$.data.status").value(409));
  }

  @Test
  void linkingAnExitedEmployeeIs422WithTheCode() throws Exception {
    when(joinService.approve(USER_ID, SHOP_ID, REQUEST_ID, JoinApproveMode.LINK, EMPLOYEE_ID))
        .thenThrow(new BaseException(ErrorCode.HR_MATCH_EXITED,
            String.format(ErrorCode.HR_MATCH_EXITED.getMessage(), "EMP-014")));

    mvc.perform(json(post(BASE + "/" + REQUEST_ID + "/approve"),
            "{\"mode\":\"LINK\",\"employeeId\":\"" + EMPLOYEE_ID + "\"}"))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.error").value("EMP-014 has exited. Create a new employee instead."));
  }

  // ---- reject ----------------------------------------------------------------------------------

  @Test
  void rejectPassesTheReasonAsGiven() throws Exception {
    when(joinService.reject(USER_ID, SHOP_ID, REQUEST_ID, "  Not hiring now  "))
        .thenReturn(new JoinRequestDecision(REQUEST_ID, JoinOutcome.REJECTED, null));

    mvc.perform(json(post(BASE + "/" + REQUEST_ID + "/reject"),
            "{\"reason\":\"  Not hiring now  \"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.id").value(REQUEST_ID))
        .andExpect(jsonPath("$.data.outcome").value("REJECTED"))
        .andExpect(jsonPath("$.data.employeeId").doesNotExist());
  }

  @Test
  void rejectWithoutABodyMeansNoReason() throws Exception {
    when(joinService.reject(USER_ID, SHOP_ID, REQUEST_ID, null))
        .thenReturn(new JoinRequestDecision(REQUEST_ID, JoinOutcome.REJECTED, null));

    mvc.perform(asManager(post(BASE + "/" + REQUEST_ID + "/reject")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.outcome").value("REJECTED"));
    mvc.perform(json(post(BASE + "/" + REQUEST_ID + "/reject"), "{}"))
        .andExpect(status().isOk());
    verify(joinService, org.mockito.Mockito.times(2)).reject(USER_ID, SHOP_ID, REQUEST_ID, null);
  }

  @Test
  void rejectRefusesAReasonOver200CharactersBeforeTheService() throws Exception {
    mvc.perform(json(post(BASE + "/" + REQUEST_ID + "/reject"),
            "{\"reason\":\"" + "x".repeat(201) + "\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("The reason must be at most 200 characters"));
    verifyNoInteractions(joinService);
  }

  @Test
  void rejectRefusesAMalformedIdBeforeTheService() throws Exception {
    mvc.perform(json(post(BASE + "/zzz/reject"), "{}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("id must be a 24-character id"));
    verifyNoInteractions(joinService);
  }

  @Test
  void rejectWithoutPermissionIs403() throws Exception {
    when(joinService.reject(eq(USER_ID), eq(SHOP_ID), eq(REQUEST_ID), isNull()))
        .thenThrow(denied());

    mvc.perform(json(post(BASE + "/" + REQUEST_ID + "/reject"), "{}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.error").value(DENIED_TEXT));
  }

  @Test
  void aServiceSideValidationRefusalIs400() throws Exception {
    when(joinService.reject(eq(USER_ID), eq(SHOP_ID), eq(REQUEST_ID), anyString()))
        .thenThrow(new ValidationException("The reason must be at most 200 characters"));

    mvc.perform(json(post(BASE + "/" + REQUEST_ID + "/reject"), "{\"reason\":\"x\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("The reason must be at most 200 characters"));
  }

  // ---- bulk ------------------------------------------------------------------------------------

  @Test
  void bulkApproveAnswersThePartition() throws Exception {
    when(joinService.bulkApprove(USER_ID, SHOP_ID, List.of(REQUEST_ID, OTHER_REQUEST_ID)))
        .thenReturn(new BulkJoinResult(1,
            List.of(new BulkJoinResult.Skipped(OTHER_REQUEST_ID,
                BulkJoinResult.SkipReason.POSSIBLE_MATCH)),
            List.of()));

    mvc.perform(json(post(BASE + "/bulk/approve"),
            "{\"ids\":[\"" + REQUEST_ID + "\",\"" + OTHER_REQUEST_ID + "\"]}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.approved").value(1))
        .andExpect(jsonPath("$.data.skipped.length()").value(1))
        .andExpect(jsonPath("$.data.skipped[0].id").value(OTHER_REQUEST_ID))
        .andExpect(jsonPath("$.data.skipped[0].reason").value("POSSIBLE_MATCH"))
        .andExpect(jsonPath("$.data.failed.length()").value(0));
  }

  @Test
  void bulkApproveDedupesAndTrimsIdsBeforeTheService() throws Exception {
    when(joinService.bulkApprove(USER_ID, SHOP_ID, List.of(REQUEST_ID)))
        .thenReturn(new BulkJoinResult(1, List.of(), List.of()));

    mvc.perform(json(post(BASE + "/bulk/approve"),
            "{\"ids\":[\" " + REQUEST_ID + " \",\"" + REQUEST_ID + "\",null]}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.approved").value(1));
    verify(joinService).bulkApprove(USER_ID, SHOP_ID, List.of(REQUEST_ID));
  }

  @Test
  void bulkApproveRefusesNoIdsAndMoreThan50BeforeTheService() throws Exception {
    mvc.perform(json(post(BASE + "/bulk/approve"), "{\"ids\":[]}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("Select at least one request"));
    mvc.perform(json(post(BASE + "/bulk/approve"), "{}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("Select at least one request"));

    StringBuilder ids = new StringBuilder();
    for (int i = 0; i < 51; i++) {
      ids.append(i > 0 ? "," : "").append('"').append(String.format("%024x", i + 1)).append('"');
    }
    mvc.perform(json(post(BASE + "/bulk/approve"), "{\"ids\":[" + ids + "]}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("Select at most 50 requests at a time"));
    verifyNoInteractions(joinService);
  }

  @Test
  void bulkApproveRefusesAMalformedIdBeforeTheService() throws Exception {
    mvc.perform(json(post(BASE + "/bulk/approve"),
            "{\"ids\":[\"" + REQUEST_ID + "\",\"req-2\"]}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("ids must be a 24-character id"));
    verifyNoInteractions(joinService);
  }

  @Test
  void bulkApproveWithoutPermissionIs403() throws Exception {
    when(joinService.bulkApprove(eq(USER_ID), eq(SHOP_ID), any())).thenThrow(denied());

    mvc.perform(json(post(BASE + "/bulk/approve"), "{\"ids\":[\"" + REQUEST_ID + "\"]}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.error").value(DENIED_TEXT));
  }

  @Test
  void bulkRejectPassesTheIdsAndTheReason() throws Exception {
    when(joinService.bulkReject(USER_ID, SHOP_ID, List.of(REQUEST_ID, OTHER_REQUEST_ID),
        "Position filled"))
        .thenReturn(new BulkJoinResult(2, List.of(), List.of()));

    mvc.perform(json(post(BASE + "/bulk/reject"),
            "{\"ids\":[\"" + REQUEST_ID + "\",\"" + OTHER_REQUEST_ID
                + "\"],\"reason\":\"Position filled\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.approved").value(2))
        .andExpect(jsonPath("$.data.skipped.length()").value(0))
        .andExpect(jsonPath("$.data.failed.length()").value(0));
  }

  @Test
  void bulkRejectReportsFailuresWithTheirText() throws Exception {
    when(joinService.bulkReject(USER_ID, SHOP_ID, List.of(REQUEST_ID, OTHER_REQUEST_ID), null))
        .thenReturn(new BulkJoinResult(1, List.of(),
            List.of(new BulkJoinResult.Failed(OTHER_REQUEST_ID,
                "This request was already handled"))));

    mvc.perform(json(post(BASE + "/bulk/reject"),
            "{\"ids\":[\"" + REQUEST_ID + "\",\"" + OTHER_REQUEST_ID + "\"]}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.approved").value(1))
        .andExpect(jsonPath("$.data.failed[0].id").value(OTHER_REQUEST_ID))
        .andExpect(jsonPath("$.data.failed[0].message").value("This request was already handled"));
  }

  @Test
  void bulkRejectRefusesAnOverlongReasonBeforeTheService() throws Exception {
    mvc.perform(json(post(BASE + "/bulk/reject"),
            "{\"ids\":[\"" + REQUEST_ID + "\"],\"reason\":\"" + "x".repeat(201) + "\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("The reason must be at most 200 characters"));
    verifyNoInteractions(joinService);
  }

  @Test
  void bulkRejectWithoutPermissionIs403() throws Exception {
    when(joinService.bulkReject(eq(USER_ID), eq(SHOP_ID), any(), isNull())).thenThrow(denied());

    mvc.perform(json(post(BASE + "/bulk/reject"), "{\"ids\":[\"" + REQUEST_ID + "\"]}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.error").value(DENIED_TEXT));
  }
}
