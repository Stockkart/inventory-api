// Feature: worker-app, Property 1: Name and phone rules are the same everywhere
package com.inventory.user.validation;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.inventory.common.exception.ValidationException;
import com.inventory.common.util.PhoneNumbers;
import com.inventory.user.domain.model.UserRole;
import com.inventory.user.rest.dto.request.AuthSource;
import com.inventory.user.rest.dto.request.SignupRequest;
import java.util.Optional;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * The {@code AuthValidator} half of design Property 1, Java side: {@link
 * AuthValidator#validateName} accepts a string iff its trimmed length is 2–100 and then answers the
 * trimmed name; {@link AuthValidator#normalizeIndianMobile} is {@link PhoneNumbers#normalise} with
 * a {@link ValidationException} in place of the empty answer; and a WORKER sign-up request, on the
 * password path or the Google path, is accepted iff its phone normalises (and, on the password
 * path, its name is valid), after which the request carries the 10 digits and the trimmed name.
 * Trimming means Unicode whitespace (Java {@code strip}, the predicate behind {@code
 * StringUtils.hasText} and JavaScript's {@code trim}), so the "Name is required" check and the
 * 2–100 rule refuse the same whitespace-only names.
 *
 * <p>The {@code PhoneNumbers} half (the rule itself and the shared fixture) is {@code
 * PhoneNumbersProperties} in {@code core/common}.
 *
 * <p><b>Validates: Requirements 1.2, 1.4</b>
 */
class AuthValidatorWorkerProperties {

  private static final String NAME_CHARS =
      "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ .'-éñüशर्माअ";

  /** ASCII whitespace and Unicode spaces (em space, ideographic space); all of it is trimmed. */
  private static final String PADDING = " \t\n\r\u2003\u3000";

  private static final String VALID_NAME = "Priya Sharma";

  private final AuthValidator validator = new AuthValidator();

  // --- the name rule, written independently of AuthValidator ---

  /** Property 1's name rule as a reference model. "Trimming" is Unicode whitespace, as in JS. */
  static boolean nameRule(String raw) {
    if (raw == null) {
      return false;
    }
    int length = raw.strip().length();
    return length >= 2 && length <= 100;
  }

  /** The trimmed name the validator should hand back for an accepted {@code raw}. */
  static String trimmed(String raw) {
    return raw.strip();
  }

  // --- arbitraries ---

  /** Names around and across both length boundaries, padded, plus arbitrary text and null. */
  @Provide
  Arbitrary<String> names() {
    Arbitrary<String> anyLength = Arbitraries.strings().withChars(NAME_CHARS).ofMaxLength(104);
    Arbitrary<String> atBoundary =
        Arbitraries.of(0, 1, 2, 3, 99, 100, 101, 102)
            .flatMap(n -> Arbitraries.strings().withChars(NAME_CHARS).ofLength(n));
    Arbitrary<String> pad = Arbitraries.strings().withChars(PADDING).ofMaxLength(3);
    Arbitrary<String> padded =
        Combinators.combine(pad, Arbitraries.oneOf(anyLength, atBoundary), pad)
            .as((before, core, after) -> before + core + after);
    Arbitrary<String> whitespaceOnly =
        Arbitraries.strings().withChars(PADDING + "\u00A0").ofMaxLength(4);
    Arbitrary<String> unicode = Arbitraries.strings().ofMaxLength(110);
    return Arbitraries.oneOf(padded, padded, whitespaceOnly, unicode).injectNull(0.03);
  }

  /** Phones: formatted Indian mobiles, phone-like text, arbitrary text and null. */
  @Provide
  Arbitrary<String> phones() {
    Arbitrary<String> ten = Arbitraries.strings().withChars("0123456789").ofLength(10);
    Arbitrary<String> formatted =
        Combinators.combine(
                ten,
                Arbitraries.of("", "", "91", "+91", "+91 ", "+91-", "(91) "),
                Arbitraries.of("", "", " ", "-", "."))
            .as((d, prefix, sep) -> prefix + d.substring(0, 5) + sep + d.substring(5));
    Arbitrary<String> phoneLike =
        Arbitraries.strings().withChars("00112233445566778899 -+().abc०१२").ofMaxLength(16);
    Arbitrary<String> unicode = Arbitraries.strings().ofMaxLength(16);
    return Arbitraries.oneOf(formatted, phoneLike, unicode).injectNull(0.05);
  }

  /** Every spelling of {@code source} the validator accepts; its rule is R1.8, not Property 1. */
  @Provide
  Arbitrary<String> sources() {
    return Arbitraries.of(null, "web", "worker-app", " Worker-App ", "WEB");
  }

  @Provide
  Arbitrary<String> signupTypes() {
    return Arbitraries.of("google", "Google", "facebook");
  }

  private static SignupRequest workerPasswordSignup(String name, String phone, String source) {
    SignupRequest r = new SignupRequest();
    r.setName(name);
    r.setEmail("priya@example.com");
    r.setPassword("long-enough-password");
    r.setPhone(phone);
    r.setRole(UserRole.WORKER);
    r.setSource(source);
    return r;
  }

  private static SignupRequest workerGoogleSignup(String phone, String signupType, String source) {
    SignupRequest r = new SignupRequest();
    r.setIdToken("google-id-token");
    r.setSignupType(signupType);
    r.setRole(UserRole.WORKER);
    r.setPhone(phone);
    r.setSource(source);
    return r;
  }

  private static void assertAcceptedWorker(SignupRequest r, String phone, String source) {
    assertEquals(PhoneNumbers.normalise(phone).orElseThrow(), r.getPhone());
    assertEquals(UserRole.WORKER, r.getRole());
    assertNull(r.getShopId(), "a worker sign-up never names a shop");
    assertEquals(AuthSource.normalise(source), r.getSource());
  }

  // --- properties ---

  @Property(tries = 300)
  void validateNameAcceptsExactlyTwoToOneHundredCharactersAfterTrimming(
      @ForAll("names") String raw) {
    boolean expected = nameRule(raw);

    assertEquals(expected, AuthValidator.isValidName(raw), "<" + raw + ">");
    if (expected) {
      assertEquals(trimmed(raw), AuthValidator.validateName(raw), "<" + raw + ">");
    } else {
      assertThrows(ValidationException.class, () -> AuthValidator.validateName(raw), "<" + raw + ">");
    }
  }

  @Property(tries = 300)
  void normalizeIndianMobileIsTheSharedPhoneRuleWithAnExceptionForNo(
      @ForAll("phones") String raw) {
    Optional<String> shared = PhoneNumbers.normalise(raw);

    if (shared.isPresent()) {
      assertEquals(shared.get(), AuthValidator.normalizeIndianMobile(raw), "<" + raw + ">");
    } else {
      assertThrows(
          ValidationException.class, () -> AuthValidator.normalizeIndianMobile(raw), "<" + raw + ">");
    }
  }

  @Property(tries = 300)
  void aWorkerPasswordSignupIsAcceptedIffItsNameIsValidAndItsPhoneNormalises(
      @ForAll("names") String name, @ForAll("phones") String phone, @ForAll("sources") String source) {
    SignupRequest r = workerPasswordSignup(name, phone, source);
    boolean expected = nameRule(name) && PhoneNumbers.isValid(phone);

    if (expected) {
      assertDoesNotThrow(() -> validator.validateSignupRequest(r), "<" + name + "> <" + phone + ">");
      assertEquals(trimmed(name), r.getName());
      assertAcceptedWorker(r, phone, source);
    } else {
      assertThrows(
          ValidationException.class,
          () -> validator.validateSignupRequest(r),
          "<" + name + "> <" + phone + ">");
    }
  }

  @Property(tries = 300)
  void aWorkerPasswordSignupWithAValidNameIsAcceptedIffItsPhoneNormalises(
      @ForAll("phones") String phone) {
    SignupRequest r = workerPasswordSignup(VALID_NAME, phone, null);

    if (PhoneNumbers.isValid(phone)) {
      assertDoesNotThrow(() -> validator.validateSignupRequest(r), "<" + phone + ">");
      assertAcceptedWorker(r, phone, null);
      assertEquals(VALID_NAME, r.getName());
    } else {
      assertThrows(
          ValidationException.class, () -> validator.validateSignupRequest(r), "<" + phone + ">");
      assertFalse(PhoneNumbers.isValid(r.getPhone()), "a refused phone is not rewritten to a valid one");
    }
  }

  @Property(tries = 300)
  void aWorkerGoogleSignupIsAcceptedIffItsPhoneNormalises(
      @ForAll("phones") String phone,
      @ForAll("signupTypes") String signupType,
      @ForAll("sources") String source) {
    SignupRequest r = workerGoogleSignup(phone, signupType, source);

    if (PhoneNumbers.isValid(phone)) {
      assertDoesNotThrow(() -> validator.validateSignupRequest(r), "<" + phone + ">");
      assertAcceptedWorker(r, phone, source);
    } else {
      assertThrows(
          ValidationException.class, () -> validator.validateSignupRequest(r), "<" + phone + ">");
    }
  }

  @Property(tries = 200)
  void bothSignupPathsAgreeOnEveryPhone(@ForAll("phones") String phone) {
    SignupRequest password = workerPasswordSignup(VALID_NAME, phone, null);
    SignupRequest google = workerGoogleSignup(phone, "google", null);

    boolean passwordAccepted = accepts(password);
    boolean googleAccepted = accepts(google);

    assertEquals(passwordAccepted, googleAccepted, "the two paths disagree on <" + phone + ">");
    assertEquals(PhoneNumbers.isValid(phone), passwordAccepted, "<" + phone + ">");
    if (passwordAccepted) {
      assertEquals(password.getPhone(), google.getPhone());
      assertTrue(PhoneNumbers.isValid(password.getPhone()));
    }
  }

  private boolean accepts(SignupRequest r) {
    try {
      validator.validateSignupRequest(r);
      return true;
    } catch (ValidationException e) {
      return false;
    }
  }
}
