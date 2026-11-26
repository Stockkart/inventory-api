// Feature: worker-app, Property 25: Photo rules
package com.inventory.user.service;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import javax.imageio.ImageIO;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tuple;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * The server half of design Property 25.
 *
 * <p><b>put.</b> For any image width, height, encoding, pixel content, trailing bytes and declared
 * content type, {@link UserPhotoService#put} accepts iff the bytes are a JPEG or PNG, {@code width
 * ≤ 512}, {@code height ≤ 512}, {@code bytes ≤ 300 KB} and the declared type is JPEG or PNG; an
 * accepted photo is stored byte-for-byte under the caller's id with the type the bytes decode as.
 * The byte limit is driven two ways: incompressible pixel noise (a 512 × 512 noisy PNG is about
 * 790 KB) and bytes appended after the image's end marker, which both readers ignore, so size and
 * dimensions vary independently. "Declared type is JPEG or PNG" follows the service's reading of a
 * {@code Content-Type}: case-insensitive, parameters after {@code ;} ignored, and the common
 * non-registered alias {@code image/jpg} counted as JPEG.
 *
 * <p><b>mayViewPhoto.</b> For any graph of accounts, shops, memberships, join requests and
 * "add and edit employees" grants, {@link UserPhotoService#mayViewPhoto} is true iff {@code viewer
 * == owner}, or the viewer holds the grant in a shop with a Pending or Rejected (≤ 90 d) request
 * from the owner, or both are active members of one shop. HR owns requests and grants, so that
 * clause reaches the service through {@link WorkerPhotoAccessPort}; here a fake port evaluates it
 * over the generated graph (the real {@code WorkerPhotoAccessPortImpl} is tested in {@code
 * core/hr}), and with no port wired the clause is false. "Active member of a shop" is an active
 * membership row, or an active account whose {@code shopId} is that shop (legacy owners and the
 * shop a worker claims, DD1). {@link UserPhotoService#get} refuses with {@code ACCESS_DENIED}
 * exactly when the rule says no, without consulting the photo store.
 *
 * <p>The phone's {@code shrinkToLimit} is the other half (task 14.8, fast-check).
 *
 * <p><b>Validates: Requirements 1.7, 11.5</b>
 */
class UserPhotoServiceProperties {

  // =====================================================================================
  // put (R1.7)
  // =====================================================================================

  private static final int MAX_SIDE = 512;
  private static final int MAX_BYTES = 300 * 1024;

  /** One upload attempt: how the image is made, and what the client says it is. */
  record Candidate(
      int width, int height, String format, boolean noisy, long seed, int paddedTo,
      String declaredType) {

    boolean isJpegOrPng() {
      return "png".equals(format) || "jpeg".equals(format);
    }

    /** The content type the service should store for an accepted upload. */
    String expectedStoredType() {
      return "png".equals(format) ? UserPhotoService.PNG : UserPhotoService.JPEG;
    }

    /** Encodes the image (or junk for {@code "junk"}) and pads it to {@code paddedTo} if longer. */
    byte[] bytes() {
      byte[] body;
      if ("junk".equals(format)) {
        body = new byte[Math.max(1, width)];
        new Random(seed).nextBytes(body);
      } else {
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        int[] pixels = ((DataBufferInt) img.getRaster().getDataBuffer()).getData();
        if (noisy) {
          Random random = new Random(seed);
          for (int i = 0; i < pixels.length; i++) {
            pixels[i] = random.nextInt() & 0xFFFFFF;
          }
        } else {
          Arrays.fill(pixels, 0x1E3A5F);
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
          if (!ImageIO.write(img, format, out)) {
            throw new IllegalStateException("no writer for " + format);
          }
        } catch (IOException e) {
          throw new IllegalStateException(e);
        }
        body = out.toByteArray();
      }
      if (paddedTo <= body.length) {
        return body;
      }
      byte[] padded = Arrays.copyOf(body, paddedTo);
      byte[] tail = new byte[paddedTo - body.length];
      new Random(seed ^ 0x5DEECE66DL).nextBytes(tail);
      System.arraycopy(tail, 0, padded, body.length, tail.length);
      return padded;
    }
  }

  /** Property 25's reading of a declared {@code Content-Type}, written without the service. */
  static boolean declaredJpegOrPng(String declared) {
    if (declared == null) {
      return false;
    }
    String type = declared.strip().toLowerCase(Locale.ROOT);
    int semicolon = type.indexOf(';');
    if (semicolon >= 0) {
      type = type.substring(0, semicolon).strip();
    }
    return type.equals("image/jpeg") || type.equals("image/png") || type.equals("image/jpg");
  }

  /** Sides around and across the 512 boundary, plus the full 1..600 range. */
  @Provide
  Arbitrary<Integer> sides() {
    return Arbitraries.frequencyOf(
        Tuple.of(3, Arbitraries.of(1, 2, 7, 64, 300, 511, 512, 513, 600)),
        Tuple.of(2, Arbitraries.integers().between(1, 600)));
  }

  /** Mostly real photo encodings; sometimes other image formats or bytes that are no image. */
  @Provide
  Arbitrary<String> formats() {
    return Arbitraries.frequencyOf(
        Tuple.of(5, Arbitraries.of("png", "jpeg")),
        Tuple.of(1, Arbitraries.of("gif", "bmp")),
        Tuple.of(1, Arbitraries.just("junk")));
  }

  /** Declared types: exact, alias, odd case, with parameters, wrong, empty or absent. */
  @Provide
  Arbitrary<String> declaredTypes() {
    return Arbitraries.frequencyOf(
        Tuple.of(6, Arbitraries.of("image/jpeg", "image/png", "image/jpg", "IMAGE/PNG",
            " image/jpeg; charset=binary", "Image/Png ;q=1")),
        Tuple.of(2, Arbitraries.of("image/gif", "image/webp", "text/plain",
            "application/octet-stream", "image/pngx", "image/")),
        Tuple.of(1, Arbitraries.of("", "   ")))
        .injectNull(0.05);
  }

  /** Where to pad to: nowhere, exactly the limit, one over, or somewhere around it. */
  @Provide
  Arbitrary<Integer> paddings() {
    return Arbitraries.frequencyOf(
        Tuple.of(4, Arbitraries.just(0)),
        Tuple.of(2, Arbitraries.of(MAX_BYTES, MAX_BYTES + 1)),
        Tuple.of(2, Arbitraries.integers().between(MAX_BYTES - 4096, MAX_BYTES + 4096)),
        Tuple.of(1, Arbitraries.integers().between(1, 2048)));
  }

  @Provide
  Arbitrary<Candidate> candidates() {
    return Combinators.combine(
            sides(), sides(), formats(),
            Arbitraries.of(true, false, false),
            Arbitraries.longs(),
            paddings(),
            declaredTypes())
        .as(Candidate::new);
  }

  @Property(tries = 150)
  void putAcceptsExactlyAJpegOrPngOfAtMost512By512And300Kb(
      @ForAll("candidates") Candidate candidate) {
    byte[] bytes = candidate.bytes();
    boolean expected = candidate.isJpegOrPng()
        && candidate.width() <= MAX_SIDE
        && candidate.height() <= MAX_SIDE
        && bytes.length <= MAX_BYTES
        && declaredJpegOrPng(candidate.declaredType());

    UserPhotoRepository photos = mock(UserPhotoRepository.class);
    when(photos.save(any(UserPhoto.class))).thenAnswer(i -> i.getArgument(0));
    UserPhotoService service = new UserPhotoService();
    ReflectionTestUtils.setField(service, "userPhotoRepository", photos);
    String label = candidate + " length=" + bytes.length;

    if (expected) {
      UserPhoto saved = service.put("w-1", bytes, candidate.declaredType());
      assertEquals("w-1", saved.getUserId(), label);
      assertEquals(candidate.expectedStoredType(), saved.getContentType(), label);
      assertArrayEquals(bytes, saved.getBytes(), label);
      verify(photos, times(1)).save(any(UserPhoto.class));
    } else {
      assertThrows(ValidationException.class,
          () -> service.put("w-1", bytes, candidate.declaredType()), label);
      verify(photos, never()).save(any());
    }
  }

  // =====================================================================================
  // mayViewPhoto (R11.5)
  // =====================================================================================

  private static final List<String> USERS = List.of("u0", "u1", "u2", "u3", "u4", "u5");
  private static final List<String> SHOPS = List.of("s0", "s1", "s2", "s3");

  /** The fake HR side's clock; request ages are measured against it, so day 90 is not racy. */
  private static final Instant NOW = Instant.parse("2025-06-15T09:30:00Z");

  record Account(String userId, boolean active, String shopId) {}

  record Membership(String userId, String shopId, boolean active) {}

  /** HR's join-request states, mirrored here so {@code core/user} tests need nothing from HR. */
  enum RequestStatus { PENDING, APPROVING, APPROVED, REJECTED, WITHDRAWN }

  /** {@code decidedDaysAgo} is null for an undecided request (or a rejection with no time). */
  record Request(String userId, String shopId, RequestStatus status, Integer decidedDaysAgo) {

    Instant decidedAt() {
      return decidedDaysAgo == null ? null : NOW.minus(Duration.ofDays(decidedDaysAgo));
    }
  }

  /** {@code approverUserId} holds "add and edit employees" in {@code shopId}. */
  record Grant(String approverUserId, String shopId) {}

  record Graph(
      List<Account> accounts,
      List<Membership> memberships,
      List<Request> requests,
      Set<Grant> grants,
      boolean portWired) {

    // --- the three clauses of Property 25, written from the graph, not from the service ---

    Set<String> activeShopsOf(String userId) {
      Set<String> shops = new HashSet<>();
      for (Membership m : memberships) {
        if (m.active() && m.userId().equals(userId) && m.shopId() != null) {
          shops.add(m.shopId());
        }
      }
      for (Account a : accounts) {
        if (a.active() && a.userId().equals(userId) && a.shopId() != null) {
          shops.add(a.shopId());
        }
      }
      return shops;
    }

    boolean coMembers(String viewer, String owner) {
      Set<String> shared = new HashSet<>(activeShopsOf(viewer));
      shared.retainAll(activeShopsOf(owner));
      return !shared.isEmpty();
    }

    boolean approverOfOwnersRequest(String viewer, String owner) {
      for (Request r : requests) {
        if (!r.userId().equals(owner) || r.shopId() == null) {
          continue;
        }
        boolean visible = r.status() == RequestStatus.PENDING
            || (r.status() == RequestStatus.REJECTED
                && r.decidedDaysAgo() != null && r.decidedDaysAgo() <= 90);
        if (visible && grants.contains(new Grant(viewer, r.shopId()))) {
          return true;
        }
      }
      return false;
    }

    boolean expectedMayView(String viewer, String owner) {
      if (viewer == null || owner == null) {
        return false;
      }
      return viewer.equals(owner)
          || (portWired && approverOfOwnersRequest(viewer, owner))
          || coMembers(viewer, owner);
    }

    // --- the service wired to this graph ---

    UserPhotoService service(UserPhotoRepository photos) {
      UserShopMembershipRepository membershipRepository = mock(UserShopMembershipRepository.class);
      when(membershipRepository.findByUserIdAndActiveTrue(anyString())).thenAnswer(i -> {
        String userId = i.getArgument(0);
        return memberships.stream()
            .filter(m -> m.active() && m.userId().equals(userId))
            .map(m -> {
              UserShopMembership row = new UserShopMembership();
              row.setMembershipId(m.userId() + "@" + m.shopId());
              row.setUserId(m.userId());
              row.setShopId(m.shopId());
              row.setRole(UserRole.CASHIER);
              row.setActive(true);
              return row;
            })
            .toList();
      });

      UserAccountRepository accountRepository = mock(UserAccountRepository.class);
      when(accountRepository.findById(anyString())).thenAnswer(i -> {
        String userId = i.getArgument(0);
        return accounts.stream()
            .filter(a -> a.userId().equals(userId))
            .findFirst()
            .map(a -> {
              UserAccount account = new UserAccount();
              account.setUserId(a.userId());
              account.setRole(UserRole.WORKER);
              account.setActive(a.active());
              account.setShopId(a.shopId());
              return account;
            });
      });

      UserPhotoService service = new UserPhotoService();
      ReflectionTestUtils.setField(service, "userPhotoRepository", photos);
      ReflectionTestUtils.setField(service, "userAccountRepository", accountRepository);
      ReflectionTestUtils.setField(service, "membershipRepository", membershipRepository);
      ReflectionTestUtils.setField(service, "workerPhotoAccessPort",
          portWired ? new FakeWorkerPhotoAccessPort(this) : null);
      return service;
    }
  }

  /**
   * HR's answer over the generated graph, in the shape of {@code WorkerPhotoAccessPortImpl}: the
   * shops where the owner has a Pending or recently Rejected request, then whether the viewer holds
   * the grant in any of them.
   */
  static final class FakeWorkerPhotoAccessPort implements WorkerPhotoAccessPort {

    private final Graph graph;

    FakeWorkerPhotoAccessPort(Graph graph) {
      this.graph = graph;
    }

    @Override
    public boolean mayViewRequestPhoto(String viewerUserId, String ownerUserId) {
      if (viewerUserId == null || viewerUserId.isBlank()
          || ownerUserId == null || ownerUserId.isBlank()) {
        return false;
      }
      Instant cutoff = NOW.minus(Duration.ofDays(90));
      Set<String> shops = new LinkedHashSet<>();
      for (Request r : graph.requests()) {
        if (!ownerUserId.equals(r.userId()) || r.shopId() == null) {
          continue;
        }
        boolean visible = switch (r.status()) {
          case PENDING -> true;
          case REJECTED -> r.decidedAt() != null && !r.decidedAt().isBefore(cutoff);
          default -> false;
        };
        if (visible) {
          shops.add(r.shopId());
        }
      }
      for (String shopId : shops) {
        if (graph.grants().contains(new Grant(viewerUserId, shopId))) {
          return true;
        }
      }
      return false;
    }
  }

  // --- arbitraries ---

  @Provide
  Arbitrary<String> userIds() {
    return Arbitraries.of(USERS);
  }

  @Provide
  Arbitrary<String> shopIds() {
    return Arbitraries.of(SHOPS);
  }

  @Provide
  Arbitrary<List<Account>> accountLists() {
    Arbitrary<Account> account = Combinators.combine(
            userIds(),
            Arbitraries.of(true, true, true, false),
            shopIds().injectNull(0.4))
        .as(Account::new);
    return account.list().ofMaxSize(USERS.size()).uniqueElements(Account::userId);
  }

  @Provide
  Arbitrary<List<Membership>> membershipLists() {
    Arbitrary<Membership> membership = Combinators.combine(
            userIds(), shopIds(), Arbitraries.of(true, true, true, false))
        .as(Membership::new);
    return membership.list().ofMaxSize(10);
  }

  /** Rejection ages around the 90-day edge and far from it; null for "no decision time". */
  @Provide
  Arbitrary<Integer> decidedDaysAgo() {
    return Arbitraries.of(0, 1, 30, 89, 90, 91, 120, 400).injectNull(0.15);
  }

  @Provide
  Arbitrary<List<Request>> requestLists() {
    Arbitrary<Request> request = Combinators.combine(
            userIds(),
            shopIds().injectNull(0.05),
            Arbitraries.frequencyOf(
                Tuple.of(3, Arbitraries.of(RequestStatus.PENDING, RequestStatus.REJECTED)),
                Tuple.of(1, Arbitraries.of(RequestStatus.APPROVING, RequestStatus.APPROVED,
                    RequestStatus.WITHDRAWN))),
            decidedDaysAgo())
        .as(Request::new);
    return request.list().ofMaxSize(6);
  }

  @Provide
  Arbitrary<Set<Grant>> grantSets() {
    return Combinators.combine(userIds(), shopIds()).as(Grant::new).set().ofMaxSize(8);
  }

  @Provide
  Arbitrary<Graph> graphs() {
    return Combinators.combine(
            accountLists(), membershipLists(), requestLists(), grantSets(),
            Arbitraries.of(true, true, true, false))
        .as(Graph::new);
  }

  /** A party to the question: one of the graph's ids, an id the graph never mentions, or null. */
  @Provide
  Arbitrary<String> parties() {
    return Arbitraries.frequencyOf(
        Tuple.of(8, userIds()),
        Tuple.of(1, Arbitraries.just("stranger")))
        .injectNull(0.05);
  }

  // --- properties ---

  @Property(tries = 400)
  void mayViewPhotoIsSelfOrApproverOfARequestOrActiveCoMember(
      @ForAll("graphs") Graph graph,
      @ForAll("parties") String viewer,
      @ForAll("parties") String owner) {
    UserPhotoRepository photos = mock(UserPhotoRepository.class);
    UserPhotoService service = graph.service(photos);
    String label = "viewer=" + viewer + " owner=" + owner + " " + graph;

    boolean expected = graph.expectedMayView(viewer, owner);

    assertEquals(expected, service.mayViewPhoto(viewer, owner), label);
    verify(photos, never()).findById(any());
  }

  @Property(tries = 200)
  void getServesThePhotoExactlyToThoseTheRuleAdmits(
      @ForAll("graphs") Graph graph,
      @ForAll("parties") String viewer,
      @ForAll("parties") String owner,
      @ForAll boolean ownerHasPhoto) {
    UserPhotoRepository photos = mock(UserPhotoRepository.class);
    UserPhoto photo = new UserPhoto(owner, new byte[] {1, 2, 3}, UserPhotoService.PNG, NOW);
    when(photos.findById(any())).thenAnswer(i ->
        ownerHasPhoto && owner != null && owner.equals(i.getArgument(0))
            ? Optional.of(photo) : Optional.empty());
    UserPhotoService service = graph.service(photos);
    String label = "viewer=" + viewer + " owner=" + owner + " photo=" + ownerHasPhoto + " " + graph;

    if (!graph.expectedMayView(viewer, owner)) {
      BaseException denied =
          assertThrows(BaseException.class, () -> service.get(viewer, owner), label);
      assertEquals(ErrorCode.ACCESS_DENIED, denied.getErrorCode(), label);
      verify(photos, never()).findById(any());
    } else if (ownerHasPhoto) {
      assertEquals(photo, service.get(viewer, owner), label);
    } else {
      assertThrows(ResourceNotFoundException.class, () -> service.get(viewer, owner), label);
    }
  }
}
