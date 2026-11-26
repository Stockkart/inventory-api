package com.inventory.user.service;

import com.inventory.common.constants.ErrorCode;
import com.inventory.common.exception.BaseException;
import com.inventory.common.exception.ResourceNotFoundException;
import com.inventory.common.exception.ValidationException;
import com.inventory.user.api.port.WorkerPhotoAccessPort;
import com.inventory.user.domain.model.UserAccount;
import com.inventory.user.domain.model.UserPhoto;
import com.inventory.user.domain.model.UserShopMembership;
import com.inventory.user.domain.repository.UserAccountRepository;
import com.inventory.user.domain.repository.UserPhotoRepository;
import com.inventory.user.domain.repository.UserShopMembershipRepository;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Locale;
import java.util.Set;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

/**
 * An account's profile photo (worker-app R1.7, R11.5, design DD9).
 *
 * <p>{@link #put} accepts a JPEG or PNG of at most {@value #MAX_BYTES} bytes and {@value
 * #MAX_SIDE} × {@value #MAX_SIDE} pixels, decoded with {@code ImageIO} to prove it is an image;
 * anything else is refused, never resized (the phone shrinks before upload). {@link #get} applies
 * the who-may-see rule: the account itself; an approver of a shop where the account has a Pending
 * or recently Rejected join request (asked of HR through {@link WorkerPhotoAccessPort}); and any
 * active member of a shop the account is an active member of. The photo is never on a public URL.
 */
@Slf4j
@Service
public class UserPhotoService {

  /** Largest accepted photo, in bytes (300 KB). */
  public static final int MAX_BYTES = 300 * 1024;

  /** Largest accepted width and height, in pixels. */
  public static final int MAX_SIDE = 512;

  public static final String JPEG = "image/jpeg";
  public static final String PNG = "image/png";

  @Autowired private UserPhotoRepository userPhotoRepository;

  @Autowired private UserAccountRepository userAccountRepository;

  @Autowired private UserShopMembershipRepository membershipRepository;

  /** Own-module port, implemented by HR (task 4.3); until then only self and co-members may see. */
  @Autowired(required = false)
  private WorkerPhotoAccessPort workerPhotoAccessPort;

  /**
   * Stores the account's photo, replacing any previous one.
   *
   * @param userId the account (always the caller's own)
   * @param bytes the encoded image
   * @param declaredContentType what the client said it sent; must be JPEG or PNG. The stored type
   *     is what the bytes decode as, not this value
   * @return the saved photo
   * @throws ValidationException when the type, size or dimensions break the rules or the bytes are
   *     not an image
   */
  public UserPhoto put(String userId, byte[] bytes, String declaredContentType) {
    if (userId == null || userId.isBlank()) {
      throw new ValidationException("User is required");
    }
    if (bytes == null || bytes.length == 0) {
      throw new ValidationException("Photo is required");
    }
    if (!isAcceptedType(declaredContentType)) {
      throw new ValidationException("Photo must be a JPEG or PNG");
    }
    if (bytes.length > MAX_BYTES) {
      throw new ValidationException("Photo must be at most 300 KB");
    }
    String contentType = decodeAndCheck(bytes);
    try {
      return userPhotoRepository.save(new UserPhoto(userId, bytes, contentType, Instant.now()));
    } catch (DataAccessException e) {
      log.error("Database error while saving photo of {}: {}", userId, e.getMessage(), e);
      throw new BaseException(ErrorCode.INTERNAL_SERVER_ERROR, "Error saving photo");
    }
  }

  /**
   * The owner's photo for a viewer, under the who-may-see rule (R11.5). Access is decided before
   * existence, so a viewer who may not see learns nothing about whether a photo exists.
   *
   * @throws BaseException {@code ACCESS_DENIED} (403) when the viewer may not see it
   * @throws ResourceNotFoundException when the owner has no photo
   */
  public UserPhoto get(String viewerUserId, String ownerUserId) {
    if (!mayViewPhoto(viewerUserId, ownerUserId)) {
      throw new BaseException(ErrorCode.ACCESS_DENIED, "Access denied");
    }
    return userPhotoRepository.findById(ownerUserId)
        .orElseThrow(() -> new ResourceNotFoundException("Photo", "userId", ownerUserId));
  }

  /** Removes the account's photo; nothing happens when there is none. */
  public void delete(String userId) {
    if (userId == null) {
      return;
    }
    userPhotoRepository.deleteById(userId);
  }

  /** True when the account has a photo (the join-request snapshot's {@code hasPhoto}). */
  public boolean hasPhoto(String userId) {
    return userId != null && userPhotoRepository.existsById(userId);
  }

  /**
   * The who-may-see rule (R11.5, Property 25): {@code viewer == owner}; or the viewer holds "add and
   * edit employees" in a shop with a Pending or Rejected (≤ 90 d) request from the owner (HR's
   * answer); or both are active members of one shop.
   */
  public boolean mayViewPhoto(String viewerUserId, String ownerUserId) {
    if (viewerUserId == null || ownerUserId == null) {
      return false;
    }
    if (viewerUserId.equals(ownerUserId)) {
      return true;
    }
    if (workerPhotoAccessPort != null
        && workerPhotoAccessPort.mayViewRequestPhoto(viewerUserId, ownerUserId)) {
      return true;
    }
    Set<String> viewerShops = activeShopsOf(viewerUserId);
    if (viewerShops.isEmpty()) {
      return false;
    }
    for (String shop : activeShopsOf(ownerUserId)) {
      if (viewerShops.contains(shop)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Shops the account is an active member of: its active membership rows plus its active shop
   * ({@code UserAccount.shopId}, for legacy owners without a membership row and for workers, whose
   * shop is written there first).
   */
  private Set<String> activeShopsOf(String userId) {
    Set<String> shops = new HashSet<>();
    for (UserShopMembership m : membershipRepository.findByUserIdAndActiveTrue(userId)) {
      if (m.getShopId() != null) {
        shops.add(m.getShopId());
      }
    }
    userAccountRepository.findById(userId)
        .filter(UserAccount::isActive)
        .map(UserAccount::getShopId)
        .filter(s -> s != null && !s.isBlank())
        .ifPresent(shops::add);
    return shops;
  }

  static boolean isAcceptedType(String contentType) {
    if (contentType == null) {
      return false;
    }
    String type = contentType.trim().toLowerCase(Locale.ROOT);
    int semicolon = type.indexOf(';');
    if (semicolon >= 0) {
      type = type.substring(0, semicolon).trim();
    }
    return JPEG.equals(type) || PNG.equals(type) || "image/jpg".equals(type);
  }

  /**
   * Decodes the bytes with ImageIO; returns the detected content type. Refuses anything that is
   * not a JPEG or PNG, cannot be decoded, or is wider or taller than {@link #MAX_SIDE}.
   */
  static String decodeAndCheck(byte[] bytes) {
    try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
      if (in == null) {
        throw new ValidationException("Photo is not a JPEG or PNG image");
      }
      Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
      if (!readers.hasNext()) {
        throw new ValidationException("Photo is not a JPEG or PNG image");
      }
      ImageReader reader = readers.next();
      try {
        String format = reader.getFormatName().toLowerCase(Locale.ROOT);
        String contentType;
        if ("jpeg".equals(format) || "jpg".equals(format)) {
          contentType = JPEG;
        } else if ("png".equals(format)) {
          contentType = PNG;
        } else {
          throw new ValidationException("Photo must be a JPEG or PNG");
        }
        reader.setInput(in);
        // header first: a 20000 × 20000 image is refused before its pixels are allocated
        int width = reader.getWidth(0);
        int height = reader.getHeight(0);
        if (width > MAX_SIDE || height > MAX_SIDE) {
          throw new ValidationException("Photo must be at most 512 × 512 pixels");
        }
        BufferedImage image = reader.read(0);
        if (image == null) {
          throw new ValidationException("Photo is not a JPEG or PNG image");
        }
        return contentType;
      } finally {
        reader.dispose();
      }
    } catch (IOException | RuntimeException e) {
      if (e instanceof ValidationException v) {
        throw v;
      }
      throw new ValidationException("Photo is not a JPEG or PNG image");
    }
  }
}
