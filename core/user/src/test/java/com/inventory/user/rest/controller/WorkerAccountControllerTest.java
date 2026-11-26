package com.inventory.user.rest.controller;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.inventory.common.dto.response.ApiResponse;
import com.inventory.common.exception.AuthenticationException;
import com.inventory.user.domain.model.UserAccount;
import com.inventory.user.domain.model.UserPhoto;
import com.inventory.user.domain.model.UserRole;
import com.inventory.user.mapper.UserMapper;
import com.inventory.user.rest.dto.request.UpdateProfileRequest;
import com.inventory.user.rest.dto.response.UserResponse;
import com.inventory.user.service.UserPhotoService;
import com.inventory.user.service.WorkerAccountService;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mapstruct.factory.Mappers;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockMultipartFile;

/**
 * worker-app R8.2, R1.7, R11.5: the {@code me} paths act on the token's user only; the photo answer
 * is image bytes with a private, short cache.
 */
@ExtendWith(MockitoExtension.class)
class WorkerAccountControllerTest {

  @Mock private WorkerAccountService workerAccountService;
  @Mock private UserPhotoService userPhotoService;
  @Spy private UserMapper userMapper = Mappers.getMapper(UserMapper.class);
  @InjectMocks private WorkerAccountController controller;

  private static MockHttpServletRequest signedInAs(String userId, String method, String path) {
    MockHttpServletRequest request = new MockHttpServletRequest(method, path);
    // AuthenticationInterceptor puts the token's user here
    request.setAttribute("userId", userId);
    return request;
  }

  @Test
  void profileEditActsOnTheTokensUserAndAnswersTheAccount() {
    UserAccount saved = new UserAccount();
    saved.setUserId("w-1");
    saved.setName("Priya S");
    saved.setPhone("9876543211");
    saved.setEmail("priya@example.com");
    saved.setRole(UserRole.WORKER);
    saved.setActive(true);
    when(workerAccountService.updateProfile("w-1", "Priya S", "+91 98765 43211")).thenReturn(saved);

    ResponseEntity<ApiResponse<UserResponse>> response = controller.updateProfile(
        new UpdateProfileRequest("Priya S", "+91 98765 43211"),
        signedInAs("w-1", "PUT", "/api/v1/users/me/profile"));

    UserResponse body = response.getBody().getData();
    assertEquals("w-1", body.getUserId());
    assertEquals("Priya S", body.getName());
    assertEquals("9876543211", body.getPhone());
    assertEquals(UserRole.WORKER, body.getRole());
    assertNull(body.getShopId());
  }

  @Test
  void mePathsRefuseARequestWithoutAUser() {
    MockHttpServletRequest anonymous = new MockHttpServletRequest("PUT", "/api/v1/users/me/profile");
    assertThrows(AuthenticationException.class,
        () -> controller.updateProfile(new UpdateProfileRequest("Priya", "9876543210"), anonymous));
    assertThrows(AuthenticationException.class, () -> controller.deletePhoto(anonymous));
    assertThrows(AuthenticationException.class, () -> controller.getPhoto("w-1", anonymous));
    verify(workerAccountService, never()).updateProfile(any(), any(), any());
    verify(userPhotoService, never()).delete(any());
    verify(userPhotoService, never()).get(any(), any());
  }

  @Test
  void photoUploadHandsThePartToTheServiceUnderTheTokensUser() {
    byte[] bytes = {1, 2, 3};
    when(userPhotoService.put("w-1", bytes, "image/jpeg"))
        .thenReturn(new UserPhoto("w-1", bytes, "image/jpeg", Instant.parse("2026-05-12T04:00:00Z")));

    ResponseEntity<ApiResponse<Map<String, Object>>> response = controller.putPhoto(
        new MockMultipartFile("photo", "me.jpg", "image/jpeg", bytes),
        signedInAs("w-1", "PUT", "/api/v1/users/me/photo"));

    Map<String, Object> body = response.getBody().getData();
    assertEquals("/api/v1/users/w-1/photo", body.get("photoUrl"));
    assertEquals("image/jpeg", body.get("contentType"));
    assertEquals(3, body.get("bytes"));
  }

  @Test
  void photoIsServedAsImageBytesWithAPrivateFiveMinuteCache() {
    byte[] bytes = {9, 8, 7};
    when(userPhotoService.get("m-1", "w-1"))
        .thenReturn(new UserPhoto("w-1", bytes, "image/png", Instant.now()));

    ResponseEntity<byte[]> response =
        controller.getPhoto("w-1", signedInAs("m-1", "GET", "/api/v1/users/w-1/photo"));

    assertEquals(200, response.getStatusCode().value());
    assertEquals(MediaType.IMAGE_PNG, response.getHeaders().getContentType());
    assertEquals("max-age=300, private", response.getHeaders().getCacheControl());
    assertArrayEquals(bytes, response.getBody());
  }

  @Test
  void meIsAnAliasForTheCallerOnThePhotoRead() {
    when(userPhotoService.get("w-1", "w-1"))
        .thenReturn(new UserPhoto("w-1", new byte[] {1}, "image/jpeg", Instant.now()));

    ResponseEntity<byte[]> response =
        controller.getPhoto("me", signedInAs("w-1", "GET", "/api/v1/users/me/photo"));

    assertEquals(MediaType.IMAGE_JPEG, response.getHeaders().getContentType());
    verify(userPhotoService).get("w-1", "w-1");
  }

  @Test
  void deleteRemovesTheCallersPhotoOnly() {
    ResponseEntity<ApiResponse<Map<String, Object>>> response =
        controller.deletePhoto(signedInAs("w-1", "DELETE", "/api/v1/users/me/photo"));

    assertTrue((Boolean) response.getBody().getData().get("deleted"));
    verify(userPhotoService).delete("w-1");
  }
}
