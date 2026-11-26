package com.inventory.user.rest.controller;

import com.inventory.common.constants.ErrorCode;
import com.inventory.common.dto.response.ApiResponse;
import com.inventory.common.exception.AuthenticationException;
import com.inventory.common.exception.ValidationException;
import com.inventory.metrics.annotation.Latency;
import com.inventory.metrics.annotation.RecordRequestRate;
import com.inventory.metrics.annotation.RecordStatusCodes;
import com.inventory.user.domain.model.UserPhoto;
import com.inventory.user.mapper.UserMapper;
import com.inventory.user.rest.dto.request.UpdateProfileRequest;
import com.inventory.user.rest.dto.response.UserResponse;
import com.inventory.user.service.UserPhotoService;
import com.inventory.user.service.WorkerAccountService;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * The account's own profile and photo (worker-app R1.7, R8.2, R11.5). All Bearer; the {@code me}
 * paths act on the token's user only and never take a user id. {@code GET /{userId}/photo} is the
 * one path that names another account, behind {@link UserPhotoService#mayViewPhoto}.
 */
@RestController
@RequestMapping("/api/v1/users")
@Latency(module = "user")
@RecordRequestRate(module = "user")
@RecordStatusCodes(module = "user")
public class WorkerAccountController {

  /** Seconds a browser or the app may keep a photo before asking again. */
  static final long PHOTO_MAX_AGE_SECONDS = 300;

  @Autowired private WorkerAccountService workerAccountService;

  @Autowired private UserPhotoService userPhotoService;

  @Autowired private UserMapper userMapper;

  /**
   * {@code PUT /me/profile {name?, phone}}: the account's own name and phone for any role and any
   * shop state; {@code name} absent or blank is unchanged, {@code phone} is required (R8.2, R1.11,
   * DD20).
   */
  @PutMapping("/me/profile")
  public ResponseEntity<ApiResponse<UserResponse>> updateProfile(
      @RequestBody UpdateProfileRequest body, HttpServletRequest request) {
    String userId = callerId(request);
    if (body == null) {
      throw new ValidationException("Request body is required");
    }
    UserResponse response = userMapper.toUserResponse(
        workerAccountService.updateProfile(userId, body.getName(), body.getPhone()));
    return ResponseEntity.ok(ApiResponse.success(response));
  }

  @PutMapping(value = "/me/photo", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public ResponseEntity<ApiResponse<Map<String, Object>>> putPhoto(
      @RequestPart("photo") MultipartFile photo, HttpServletRequest request) {
    String userId = callerId(request);
    byte[] bytes;
    try {
      bytes = photo.getBytes();
    } catch (IOException e) {
      throw new ValidationException("Photo could not be read");
    }
    UserPhoto saved = userPhotoService.put(userId, bytes, photo.getContentType());
    return ResponseEntity.ok(ApiResponse.success(Map.of(
        "photoUrl", photoUrl(userId),
        "contentType", saved.getContentType(),
        "bytes", saved.getBytes().length,
        "updatedAt", saved.getUpdatedAt().toString())));
  }

  @DeleteMapping("/me/photo")
  public ResponseEntity<ApiResponse<Map<String, Object>>> deletePhoto(HttpServletRequest request) {
    String userId = callerId(request);
    userPhotoService.delete(userId);
    return ResponseEntity.ok(ApiResponse.success(Map.of("deleted", true)));
  }

  /**
   * The account's photo as image bytes (not the JSON envelope), {@code Cache-Control: private,
   * max-age=300}. {@code me} is the caller. 403 when the caller may not see it, 404 when there is
   * none.
   */
  @GetMapping("/{userId}/photo")
  public ResponseEntity<byte[]> getPhoto(@PathVariable String userId, HttpServletRequest request) {
    String viewerId = callerId(request);
    String ownerId = "me".equals(userId) ? viewerId : userId;
    UserPhoto photo = userPhotoService.get(viewerId, ownerId);
    return ResponseEntity.ok()
        .contentType(MediaType.parseMediaType(photo.getContentType()))
        .cacheControl(CacheControl.maxAge(PHOTO_MAX_AGE_SECONDS, java.util.concurrent.TimeUnit.SECONDS)
            .cachePrivate())
        .body(photo.getBytes());
  }

  /** Where the photo can be fetched by whoever is allowed to (R11.5); never a public URL. */
  static String photoUrl(String userId) {
    return "/api/v1/users/" + userId + "/photo";
  }

  private static String callerId(HttpServletRequest request) {
    String userId = (String) request.getAttribute("userId");
    if (!StringUtils.hasText(userId)) {
      throw new AuthenticationException(ErrorCode.UNAUTHORIZED, "User not authenticated");
    }
    return userId;
  }
}
