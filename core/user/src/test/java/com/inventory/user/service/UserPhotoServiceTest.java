package com.inventory.user.service;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.inventory.common.constants.ErrorCode;
import com.inventory.common.exception.BaseException;
import com.inventory.common.exception.ResourceNotFoundException;
import com.inventory.common.exception.ValidationException;
import com.inventory.user.api.port.WorkerPhotoAccessPort;
import com.inventory.user.domain.model.UserAccount;
import com.inventory.user.domain.model.UserPhoto;
import com.inventory.user.domain.model.UserRole;
import com.inventory.user.domain.model.UserShopMembership;
import com.inventory.user.domain.repository.UserAccountRepository;
import com.inventory.user.domain.repository.UserPhotoRepository;
import com.inventory.user.domain.repository.UserShopMembershipRepository;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/** worker-app R1.7 (what a photo may be) and R11.5 (who may see it). */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class UserPhotoServiceTest {

  @Mock private UserPhotoRepository userPhotoRepository;
  @Mock private UserAccountRepository userAccountRepository;
  @Mock private UserShopMembershipRepository membershipRepository;
  @Mock private WorkerPhotoAccessPort workerPhotoAccessPort;
  @InjectMocks private UserPhotoService service;

  /** A small solid-colour image encoded as {@code format} ("png", "jpeg", "gif"). */
  static byte[] image(int width, int height, String format) {
    BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
    Graphics2D g = img.createGraphics();
    g.setColor(new Color(0x1e, 0x3a, 0x5f));
    g.fillRect(0, 0, width, height);
    g.dispose();
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try {
      assertTrue(ImageIO.write(img, format, out), "no writer for " + format);
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
    return out.toByteArray();
  }

  private static UserAccount account(String id, String shopId) {
    UserAccount a = new UserAccount();
    a.setUserId(id);
    a.setRole(UserRole.WORKER);
    a.setShopId(shopId);
    a.setActive(true);
    return a;
  }

  private static UserShopMembership membership(String userId, String shopId) {
    UserShopMembership m = new UserShopMembership();
    m.setUserId(userId);
    m.setShopId(shopId);
    m.setRole(UserRole.CASHIER);
    m.setActive(true);
    return m;
  }

  // --- put (R1.7) ---

  @Test
  void acceptsASmallPngAndStoresTheDetectedType() {
    when(userPhotoRepository.save(any(UserPhoto.class))).thenAnswer(i -> i.getArgument(0));
    byte[] png = image(64, 64, "png");

    UserPhoto saved = service.put("w-1", png, "image/png");

    assertEquals("w-1", saved.getUserId());
    assertEquals(UserPhotoService.PNG, saved.getContentType());
    assertArrayEquals(png, saved.getBytes());
    assertNotNull(saved.getUpdatedAt());
  }

  @Test
  void acceptsAJpegAtTheLargestAllowedSizeAndTypesItByItsBytes() {
    when(userPhotoRepository.save(any(UserPhoto.class))).thenAnswer(i -> i.getArgument(0));
    byte[] jpeg = image(512, 512, "jpeg");
    assertTrue(jpeg.length <= UserPhotoService.MAX_BYTES, "fixture must fit the limit");

    // declared as "image/jpg; charset=binary": tolerated, but the stored type is the real one
    UserPhoto saved = service.put("w-1", jpeg, "image/jpg; charset=binary");

    assertEquals(UserPhotoService.JPEG, saved.getContentType());
  }

  @Test
  void refusesAnImageWiderOrTallerThan512() {
    assertThrows(ValidationException.class, () -> service.put("w-1", image(513, 10, "png"), "image/png"));
    assertThrows(ValidationException.class, () -> service.put("w-1", image(10, 513, "png"), "image/png"));
    verify(userPhotoRepository, never()).save(any());
  }

  @Test
  void refusesMoreThan300Kb() {
    byte[] tooBig = new byte[UserPhotoService.MAX_BYTES + 1];
    assertThrows(ValidationException.class, () -> service.put("w-1", tooBig, "image/png"));
    verify(userPhotoRepository, never()).save(any());
  }

  @Test
  void refusesWhatIsNotAJpegOrPng() {
    byte[] text = "not an image".getBytes(StandardCharsets.UTF_8);
    assertThrows(ValidationException.class, () -> service.put("w-1", text, "image/png"));
    assertThrows(ValidationException.class, () -> service.put("w-1", image(8, 8, "gif"), "image/png"));
    assertThrows(ValidationException.class, () -> service.put("w-1", image(8, 8, "png"), "text/plain"));
    assertThrows(ValidationException.class, () -> service.put("w-1", image(8, 8, "png"), null));
    assertThrows(ValidationException.class, () -> service.put("w-1", new byte[0], "image/png"));
    assertThrows(ValidationException.class, () -> service.put("w-1", null, "image/png"));
    verify(userPhotoRepository, never()).save(any());
  }

  // --- who may see (R11.5) ---

  @Test
  void theAccountItselfMaySee() {
    assertTrue(service.mayViewPhoto("w-1", "w-1"));
    verify(membershipRepository, never()).findByUserIdAndActiveTrue(any());
  }

  @Test
  void anApproverOfTheAccountsRequestMaySeeThroughTheHrPort() {
    when(workerPhotoAccessPort.mayViewRequestPhoto("m-1", "w-1")).thenReturn(true);
    assertTrue(service.mayViewPhoto("m-1", "w-1"));
    verify(membershipRepository, never()).findByUserIdAndActiveTrue(any());
  }

  @Test
  void anActiveCoMemberOfOneShopMaySee() {
    when(membershipRepository.findByUserIdAndActiveTrue("m-1")).thenReturn(List.of(membership("m-1", "shop-1")));
    when(membershipRepository.findByUserIdAndActiveTrue("w-1")).thenReturn(List.of(membership("w-1", "shop-1")));
    when(userAccountRepository.findById(any())).thenReturn(Optional.empty());

    assertTrue(service.mayViewPhoto("m-1", "w-1"));
  }

  @Test
  void theAccountsActiveShopCountsAsMembershipForLegacyOwners() {
    // the owner has no membership row (pre-membership account); the worker's shop is on the account
    when(membershipRepository.findByUserIdAndActiveTrue(any())).thenReturn(List.of());
    when(userAccountRepository.findById("o-1")).thenReturn(Optional.of(account("o-1", "shop-1")));
    when(userAccountRepository.findById("w-1")).thenReturn(Optional.of(account("w-1", "shop-1")));

    assertTrue(service.mayViewPhoto("o-1", "w-1"));
  }

  @Test
  void nobodyElseMaySeeAndNoPortMeansOnlySelfAndCoMembers() {
    when(workerPhotoAccessPort.mayViewRequestPhoto(any(), any())).thenReturn(false);
    when(membershipRepository.findByUserIdAndActiveTrue("m-1")).thenReturn(List.of(membership("m-1", "shop-1")));
    when(membershipRepository.findByUserIdAndActiveTrue("w-1")).thenReturn(List.of(membership("w-1", "shop-2")));
    when(userAccountRepository.findById(any())).thenReturn(Optional.empty());
    assertFalse(service.mayViewPhoto("m-1", "w-1"));
    assertFalse(service.mayViewPhoto(null, "w-1"));
    assertFalse(service.mayViewPhoto("m-1", null));

    UserPhotoService unwired = new UserPhotoService();
    assertTrue(unwired.mayViewPhoto("w-1", "w-1"));
  }

  // --- get, delete, hasPhoto ---

  @Test
  void getChecksAccessBeforeExistence() {
    when(membershipRepository.findByUserIdAndActiveTrue(any())).thenReturn(List.of());
    when(userAccountRepository.findById(any())).thenReturn(Optional.empty());

    BaseException denied = assertThrows(BaseException.class, () -> service.get("stranger", "w-1"));

    assertEquals(ErrorCode.ACCESS_DENIED, denied.getErrorCode());
    verify(userPhotoRepository, never()).findById(any());
  }

  @Test
  void getReturnsThePhotoOrNotFound() {
    UserPhoto photo = new UserPhoto("w-1", image(8, 8, "png"), UserPhotoService.PNG, Instant.now());
    when(userPhotoRepository.findById("w-1")).thenReturn(Optional.of(photo));
    when(userPhotoRepository.findById("w-2")).thenReturn(Optional.empty());

    assertEquals(photo, service.get("w-1", "w-1"));
    assertThrows(ResourceNotFoundException.class, () -> service.get("w-2", "w-2"));
  }

  @Test
  void deleteAndHasPhoto() {
    when(userPhotoRepository.existsById("w-1")).thenReturn(true);
    assertTrue(service.hasPhoto("w-1"));
    assertFalse(service.hasPhoto("w-2"));
    assertFalse(service.hasPhoto(null));

    service.delete("w-1");
    verify(userPhotoRepository).deleteById("w-1");
    service.delete(null);
    verify(userPhotoRepository, never()).deleteById(null);
  }
}
