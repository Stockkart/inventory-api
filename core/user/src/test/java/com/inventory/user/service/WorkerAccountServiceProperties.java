// Feature: worker-app, Property 32: An account that reaches Home always has a phone (server half)
package com.inventory.user.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.inventory.common.exception.ValidationException;
import com.inventory.common.util.PhoneNumbers;
import com.inventory.user.domain.model.UserAccount;
import com.inventory.user.domain.model.UserRole;
import com.inventory.user.domain.repository.UserAccountRepository;
import java.time.Instant;
import java.util.Optional;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tuple;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * The {@code core/user} half of design Property 32 (DD20, R1.11, R1.12): for any stored account
 * (any name, any role, with or without a shop) and any string {@code p}, {@link
 * WorkerAccountService#updateProfile}{@code (userId, name, p)} with {@code name} null or blank
 * leaves the name exactly as stored and
 *
 * <ul>
 *   <li>stores {@link PhoneNumbers#normalise}{@code (p)} (always 10 digits) when {@code p}
 *       normalises, evicting the login cache once;
 *   <li>otherwise throws {@link ValidationException} (a 400) without reading or writing the
 *       account or touching the cache.
 * </ul>
 *
 * <p>The app's halves (the {@code NeedsPhone} route and the {@code update}-mode phone step) live in
 * {@code inventory-platform}; {@code WorkerAccountControllerIT} proves the same through HTTP by
 * example.
 *
 * <p><b>Validates: Requirements 1.11, 1.12</b>
 */
class WorkerAccountServiceProperties {

  private static final String USER_ID = "u-32";

  /** One stored account and a service wired to a repository that holds exactly it. */
  record Stored(UserAccount account, UserAccountRepository accounts, AuthCache cache,
      WorkerAccountService service) {

    static Stored of(String name, String phone, UserRole role, String shopId) {
      UserAccount a = new UserAccount();
      a.setUserId(USER_ID);
      a.setName(name);
      a.setEmail("u32@example.com");
      a.setPhone(phone);
      a.setRole(role);
      a.setShopId(shopId);
      a.setActive(true);
      a.setUpdatedAt(Instant.EPOCH);

      UserAccountRepository accounts = mock(UserAccountRepository.class);
      when(accounts.findById(USER_ID)).thenReturn(Optional.of(a));
      when(accounts.save(any(UserAccount.class))).thenAnswer(i -> i.getArgument(0));
      AuthCache cache = mock(AuthCache.class);

      WorkerAccountService service = new WorkerAccountService();
      ReflectionTestUtils.setField(service, "userAccountRepository", accounts);
      ReflectionTestUtils.setField(service, "authCache", cache);
      return new Stored(a, accounts, cache, service);
    }
  }

  // --- arbitraries ---

  /** What an account may already carry as a name: anything, including nothing (an old row). */
  @Provide
  Arbitrary<String> storedNames() {
    return Arbitraries.oneOf(
        Arbitraries.strings().ofMinLength(2).ofMaxLength(100),
        Arbitraries.strings().ofMaxLength(200),
        Arbitraries.of("Priya Sharma", "P", "", "   ", "a".repeat(101)),
        Arbitraries.just(null));
  }

  /** What an account may already carry as a phone: a good one, junk, or none (a web Google login). */
  @Provide
  Arbitrary<String> storedPhones() {
    return Arbitraries.oneOf(
        Arbitraries.strings().withChars("0123456789").ofLength(10),
        Arbitraries.of("", "12345", "+91 98765 43210"),
        Arbitraries.just(null));
  }

  /** The "name absent" shapes the phone step and a sparse client may send. */
  @Provide
  Arbitrary<String> absentNames() {
    return Arbitraries.oneOf(
        Arbitraries.just(null),
        Arbitraries.just(""),
        Arbitraries.strings().withChars(" \t\n\r").ofMinLength(1).ofMaxLength(6));
  }

  /**
   * Any string {@code p}: roughly half are decorated Indian mobiles the rule accepts, the rest are
   * arbitrary text, digit strings of the wrong length, blanks and null.
   */
  @Provide
  Arbitrary<String> phones() {
    Arbitrary<String> digits10 = Arbitraries.strings().withChars("0123456789").ofLength(10);
    Arbitrary<String> prefix = Arbitraries.of("", "", "91", "+91", "+91 ", "+91-", "(91) ", "91 ");
    Arbitrary<String> sep = Arbitraries.of("", "", " ", "-", ".", "  ");
    Arbitrary<String> decorated = Combinators.combine(prefix, digits10, sep, sep)
        .as((pre, d, s1, s2) -> pre + d.substring(0, 5) + s1 + d.substring(5) + s2);
    Arbitrary<String> wrongLength = Arbitraries.integers().between(0, 14).filter(n -> n != 10)
        .flatMap(n -> Arbitraries.strings().withChars("0123456789").ofLength(n));
    Arbitrary<String> junk = Arbitraries.strings().ofMaxLength(20);
    return Arbitraries.frequencyOf(
        Tuple.of(5, decorated),
        Tuple.of(2, wrongLength),
        Tuple.of(2, junk),
        Tuple.of(1, Arbitraries.of("", " ", "\t", "0" + "9876543210", "+1 9876543210")),
        Tuple.of(1, Arbitraries.just(null)));
  }

  @Provide
  Arbitrary<UserRole> roles() {
    return Arbitraries.of(UserRole.WORKER, UserRole.OWNER, UserRole.MANAGER, UserRole.CASHIER);
  }

  @Provide
  Arbitrary<String> shopIds() {
    return Arbitraries.of("shop-1", "shop-2").injectNull(0.5);
  }

  // --- the property ---

  @Property(tries = 400)
  void withNoNameTheNameIsUntouchedAndThePhoneIsStoredIffItNormalises(
      @ForAll("storedNames") String storedName,
      @ForAll("storedPhones") String storedPhone,
      @ForAll("roles") UserRole role,
      @ForAll("shopIds") String shopId,
      @ForAll("absentNames") String absentName,
      @ForAll("phones") String p) {
    Stored s = Stored.of(storedName, storedPhone, role, shopId);
    Optional<String> expected = PhoneNumbers.normalise(p);

    if (expected.isPresent()) {
      UserAccount saved = s.service().updateProfile(USER_ID, absentName, p);

      assertEquals(storedName, saved.getName(), "name untouched for absent name " + show(absentName));
      assertEquals(expected.get(), saved.getPhone());
      assertEquals(10, saved.getPhone().length(), "always the bare 10 digits");
      assertTrue(saved.getPhone().chars().allMatch(Character::isDigit));
      assertEquals(role, saved.getRole());
      assertEquals(shopId, saved.getShopId(), "a profile edit never changes the shop");
      assertEquals("u32@example.com", saved.getEmail());
      assertNotNull(saved.getUpdatedAt());
      assertTrue(saved.getUpdatedAt().isAfter(Instant.EPOCH));
      verify(s.accounts()).save(s.account());
      verify(s.cache()).evictUser(USER_ID);
    } else {
      assertThrows(ValidationException.class,
          () -> s.service().updateProfile(USER_ID, absentName, p), "refused: " + show(p));

      assertEquals(storedName, s.account().getName());
      assertEquals(storedPhone, s.account().getPhone(), "a refused update changes nothing");
      assertEquals(Instant.EPOCH, s.account().getUpdatedAt());
      verify(s.accounts(), never()).findById(any());
      verify(s.accounts(), never()).save(any());
      verify(s.cache(), never()).evictUser(any());
    }
  }

  /** The other shape the endpoint takes: a real name and a phone still change both (R8.2). */
  @Property(tries = 100)
  void withANameBothChangeUnderTheSameRules(
      @ForAll("storedNames") String storedName,
      @ForAll("phones") String p) {
    Stored s = Stored.of(storedName, "9876500000", UserRole.WORKER, null);
    String newName = "  Ramesh Kumar  ";
    Optional<String> expected = PhoneNumbers.normalise(p);

    if (expected.isPresent()) {
      UserAccount saved = s.service().updateProfile(USER_ID, newName, p);
      assertEquals("Ramesh Kumar", saved.getName());
      assertEquals(expected.get(), saved.getPhone());
    } else {
      assertThrows(ValidationException.class, () -> s.service().updateProfile(USER_ID, newName, p));
      assertEquals(storedName, s.account().getName(), "a refused phone refuses the name too");
      assertEquals("9876500000", s.account().getPhone());
      verify(s.accounts(), never()).save(any());
    }
  }

  private static String show(String s) {
    return s == null ? "null" : "'" + s.replace("\n", "\\n").replace("\t", "\\t") + "'";
  }
}
