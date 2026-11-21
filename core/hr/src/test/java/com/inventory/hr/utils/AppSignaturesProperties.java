// Feature: HR-module, Property 1: Signature round trip
// Feature: HR-module, Property 20: The punch payload never carries coordinates
package com.inventory.hr.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.inventory.hr.utils.AppSignatures.EnvelopeFields;
import com.inventory.hr.utils.AppSignatures.PunchFields;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.arbitraries.StringArbitrary;

/**
 * Canonical payloads and ECDSA verification (design Properties 1 and 20).
 *
 * <p>Property 1 — for any valid {@link PunchFields} or {@link EnvelopeFields} and any fresh P-256
 * key pair, {@code verify(spki, payload, sign(payload))} is true; changing any one field of the
 * payload (each of the ten punch fields, each envelope field), flipping one byte of the signature or
 * verifying with another key gives false; a key on {@code secp384r1} is rejected.
 *
 * <p>Property 20 — for any fields, the punch payload text is the version line plus exactly ten
 * {@code key=} lines with the fixed key set, none of them named {@code lat}, {@code lng}, {@code
 * latitude} or {@code longitude}, and no decimal number (the shape of a coordinate) appears
 * anywhere in it; the only numbers beyond the ids and the time are the two whole-metre fields.
 *
 * <p><b>Validates: Requirements 5.6, 5.12, 9.2</b>
 */
class AppSignaturesProperties {

  private static final String HEX = "0123456789abcdef";
  private static final long MAX_EPOCH_SECOND = 4_102_444_800L; // 2100-01-01T00:00:00Z

  private static final List<String> PUNCH_KEYS =
      List.of(
          "pairingId",
          "shopId",
          "employeeId",
          "punchId",
          "phoneTime",
          "offline",
          "locationChecked",
          "distanceMetres",
          "accuracyMetres",
          "mock");

  private static final Pattern DECIMAL_NUMBER = Pattern.compile("-?\\d+\\.\\d+");

  // --- arbitraries ---

  @Provide
  Arbitrary<String> objectIds() {
    return Arbitraries.strings().withChars(HEX).ofLength(24);
  }

  @Provide
  Arbitrary<String> uuids() {
    StringArbitrary hex = Arbitraries.strings().withChars(HEX);
    return Combinators.combine(
            hex.ofLength(8), hex.ofLength(4), hex.ofLength(4), hex.ofLength(4), hex.ofLength(12))
        .as((a, b, c, d, e) -> a + "-" + b + "-" + c + "-" + d + "-" + e);
  }

  @Provide
  Arbitrary<Instant> wholeSeconds() {
    return Arbitraries.longs().between(0, MAX_EPOCH_SECOND).map(Instant::ofEpochSecond);
  }

  @Provide
  Arbitrary<Integer> metres() {
    return Arbitraries.integers().between(0, 50_000).injectNull(0.25);
  }

  @Provide
  Arbitrary<PunchFields> punches() {
    return Combinators.combine(
            objectIds(),
            objectIds(),
            objectIds(),
            uuids(),
            wholeSeconds(),
            Arbitraries.of(true, false),
            Arbitraries.of(true, false),
            metres())
        .as(
            (pairing, shop, employee, punch, time, offline, located, distance) ->
                new PunchFields(
                    pairing, shop, employee, punch, time, offline, located, distance, null, false))
        .flatMap(
            base ->
                Combinators.combine(metres(), Arbitraries.of(true, false))
                    .as(
                        (accuracy, mock) ->
                            new PunchFields(
                                base.pairingId(),
                                base.shopId(),
                                base.employeeId(),
                                base.punchId(),
                                base.phoneTime(),
                                base.offline(),
                                base.locationChecked(),
                                base.distanceMetres(),
                                accuracy,
                                mock)));
  }

  @Provide
  Arbitrary<String> paths() {
    return Arbitraries.strings()
        .withCharRange('a', 'z')
        .withChars("/-_.0123456789")
        .ofMaxLength(40)
        .map(s -> "/" + s);
  }

  @Provide
  Arbitrary<String> queries() {
    return Arbitraries.strings()
        .withCharRange('a', 'z')
        .withChars("0123456789=&%+")
        .ofMaxLength(30)
        .injectNull(0.3);
  }

  @Provide
  Arbitrary<byte[]> bodies() {
    return Arbitraries.bytes().array(byte[].class).ofMaxSize(64).injectNull(0.2);
  }

  @Provide
  Arbitrary<EnvelopeFields> envelopes() {
    return Combinators.combine(
            objectIds(),
            Arbitraries.of("GET", "POST"),
            paths(),
            queries(),
            Arbitraries.longs().between(0, MAX_EPOCH_SECOND),
            uuids(),
            bodies())
        .as(EnvelopeFields::new);
  }

  /** A plausible shop or phone location, to check its text never reaches the payload. */
  @Provide
  Arbitrary<Double> latitudes() {
    return Arbitraries.doubles().between(-90, 90).ofScale(6);
  }

  @Provide
  Arbitrary<Double> longitudes() {
    return Arbitraries.doubles().between(-180, 180).ofScale(6);
  }

  // --- crypto helpers (the test signs; the server only verifies) ---

  private static KeyPair keyPair(String curve) {
    try {
      KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
      g.initialize(new ECGenParameterSpec(curve));
      return g.generateKeyPair();
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }

  private static String spki(KeyPair kp) {
    return Base64.getEncoder().encodeToString(kp.getPublic().getEncoded());
  }

  private static String sign(KeyPair kp, byte[] payload) {
    try {
      Signature s = Signature.getInstance("SHA256withECDSA");
      s.initSign(kp.getPrivate());
      s.update(payload);
      return Base64.getEncoder().encodeToString(s.sign());
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }

  /** The signature with exactly one byte changed (XOR with a non-zero mask). */
  private static String flipOneByte(String signatureBase64, int index, int mask) {
    byte[] der = Base64.getDecoder().decode(signatureBase64);
    int i = Math.floorMod(index, der.length);
    der[i] = (byte) (der[i] ^ (1 + Math.floorMod(mask, 255)));
    return Base64.getEncoder().encodeToString(der);
  }

  // --- field mutations: each returns a value that differs from the input ---

  private static String otherHexAt(String s, int index) {
    int i = Math.floorMod(index, s.length());
    while (s.charAt(i) == '-') {
      i = (i + 1) % s.length();
    }
    char c = s.charAt(i);
    char other = HEX.charAt((HEX.indexOf(c) + 1) % HEX.length());
    return s.substring(0, i) + other + s.substring(i + 1);
  }

  private static Integer otherMetres(Integer value) {
    if (value == null) {
      return 0;
    }
    if (value == 0) {
      return null;
    }
    return value + 1;
  }

  /** Ten variants of {@code f}, each differing in exactly one field. */
  private static List<PunchFields> oneFieldChanged(PunchFields f, int index, boolean plus) {
    Instant otherTime = plus ? f.phoneTime().plusSeconds(1) : f.phoneTime().minusSeconds(1);
    List<PunchFields> out = new ArrayList<>(10);
    out.add(new PunchFields(otherHexAt(f.pairingId(), index), f.shopId(), f.employeeId(), f.punchId(),
        f.phoneTime(), f.offline(), f.locationChecked(), f.distanceMetres(), f.accuracyMetres(), f.mock()));
    out.add(new PunchFields(f.pairingId(), otherHexAt(f.shopId(), index), f.employeeId(), f.punchId(),
        f.phoneTime(), f.offline(), f.locationChecked(), f.distanceMetres(), f.accuracyMetres(), f.mock()));
    out.add(new PunchFields(f.pairingId(), f.shopId(), otherHexAt(f.employeeId(), index), f.punchId(),
        f.phoneTime(), f.offline(), f.locationChecked(), f.distanceMetres(), f.accuracyMetres(), f.mock()));
    out.add(new PunchFields(f.pairingId(), f.shopId(), f.employeeId(), otherHexAt(f.punchId(), index),
        f.phoneTime(), f.offline(), f.locationChecked(), f.distanceMetres(), f.accuracyMetres(), f.mock()));
    out.add(new PunchFields(f.pairingId(), f.shopId(), f.employeeId(), f.punchId(),
        otherTime, f.offline(), f.locationChecked(), f.distanceMetres(), f.accuracyMetres(), f.mock()));
    out.add(new PunchFields(f.pairingId(), f.shopId(), f.employeeId(), f.punchId(),
        f.phoneTime(), !f.offline(), f.locationChecked(), f.distanceMetres(), f.accuracyMetres(), f.mock()));
    out.add(new PunchFields(f.pairingId(), f.shopId(), f.employeeId(), f.punchId(),
        f.phoneTime(), f.offline(), !f.locationChecked(), f.distanceMetres(), f.accuracyMetres(), f.mock()));
    out.add(new PunchFields(f.pairingId(), f.shopId(), f.employeeId(), f.punchId(),
        f.phoneTime(), f.offline(), f.locationChecked(), otherMetres(f.distanceMetres()), f.accuracyMetres(), f.mock()));
    out.add(new PunchFields(f.pairingId(), f.shopId(), f.employeeId(), f.punchId(),
        f.phoneTime(), f.offline(), f.locationChecked(), f.distanceMetres(), otherMetres(f.accuracyMetres()), f.mock()));
    out.add(new PunchFields(f.pairingId(), f.shopId(), f.employeeId(), f.punchId(),
        f.phoneTime(), f.offline(), f.locationChecked(), f.distanceMetres(), f.accuracyMetres(), !f.mock()));
    return out;
  }

  /** Seven variants of {@code f}, each differing materially in exactly one field. */
  private static List<EnvelopeFields> oneFieldChanged(EnvelopeFields f, int index, boolean plus) {
    String query = f.query() == null ? "" : f.query();
    byte[] body = f.body() == null ? new byte[0] : f.body();
    byte[] otherBody = Arrays.copyOf(body, body.length + 1);
    otherBody[body.length] = (byte) index;
    List<EnvelopeFields> out = new ArrayList<>(7);
    out.add(new EnvelopeFields(otherHexAt(f.pairingId(), index), f.method(), f.path(), f.query(),
        f.timestamp(), f.nonce(), f.body()));
    out.add(new EnvelopeFields(f.pairingId(), "GET".equals(f.method()) ? "POST" : "GET", f.path(),
        f.query(), f.timestamp(), f.nonce(), f.body()));
    out.add(new EnvelopeFields(f.pairingId(), f.method(), f.path() + "x", f.query(), f.timestamp(),
        f.nonce(), f.body()));
    out.add(new EnvelopeFields(f.pairingId(), f.method(), f.path(), query + "x", f.timestamp(),
        f.nonce(), f.body()));
    out.add(new EnvelopeFields(f.pairingId(), f.method(), f.path(), f.query(),
        plus ? f.timestamp() + 1 : f.timestamp() - 1, f.nonce(), f.body()));
    out.add(new EnvelopeFields(f.pairingId(), f.method(), f.path(), f.query(), f.timestamp(),
        otherHexAt(f.nonce(), index), f.body()));
    out.add(new EnvelopeFields(f.pairingId(), f.method(), f.path(), f.query(), f.timestamp(),
        f.nonce(), otherBody));
    return out;
  }

  // --- Property 1: Signature round trip ---

  @Property(tries = 100)
  void aPunchSignedByAFreshP256KeyVerifiesAndAnySingleChangeDoesNot(
      @ForAll("punches") PunchFields fields,
      @ForAll int index,
      @ForAll int mask,
      @ForAll boolean plus) {
    KeyPair kp = keyPair("secp256r1");
    byte[] payload = AppSignatures.punchPayload(fields);
    String signature = sign(kp, payload);

    assertTrue(AppSignatures.verify(spki(kp), payload, signature), "round trip");

    List<PunchFields> changed = oneFieldChanged(fields, index, plus);
    assertEquals(10, changed.size());
    for (int i = 0; i < changed.size(); i++) {
      byte[] other = AppSignatures.punchPayload(changed.get(i));
      assertFalse(Arrays.equals(payload, other), "field " + PUNCH_KEYS.get(i) + " did not change");
      assertFalse(
          AppSignatures.verify(spki(kp), other, signature),
          "changed " + PUNCH_KEYS.get(i) + " still verifies");
    }

    assertFalse(
        AppSignatures.verify(spki(kp), payload, flipOneByte(signature, index, mask)),
        "one flipped signature byte still verifies");

    KeyPair other = keyPair("secp256r1");
    assertFalse(AppSignatures.verify(spki(other), payload, signature), "another key verifies");
    assertTrue(AppSignatures.verify(spki(other), payload, sign(other, payload)));
  }

  @Property(tries = 100)
  void anEnvelopeSignedByAFreshP256KeyVerifiesAndAnySingleChangeDoesNot(
      @ForAll("envelopes") EnvelopeFields fields,
      @ForAll int index,
      @ForAll int mask,
      @ForAll boolean plus) {
    KeyPair kp = keyPair("secp256r1");
    byte[] payload = AppSignatures.requestEnvelope(fields);
    String signature = sign(kp, payload);

    assertTrue(AppSignatures.verify(spki(kp), payload, signature), "round trip");

    List<EnvelopeFields> changed = oneFieldChanged(fields, index, plus);
    assertEquals(7, changed.size());
    for (int i = 0; i < changed.size(); i++) {
      byte[] other = AppSignatures.requestEnvelope(changed.get(i));
      assertFalse(Arrays.equals(payload, other), "envelope field " + i + " did not change");
      assertFalse(
          AppSignatures.verify(spki(kp), other, signature),
          "changed envelope field " + i + " still verifies");
    }

    assertFalse(AppSignatures.verify(spki(kp), payload, flipOneByte(signature, index, mask)));

    KeyPair other = keyPair("secp256r1");
    assertFalse(AppSignatures.verify(spki(other), payload, signature));
  }

  @Property(tries = 100)
  void aKeyOnAnotherCurveIsRejectedEvenWithItsOwnValidSignature(
      @ForAll("punches") PunchFields fields) {
    KeyPair p384 = keyPair("secp384r1");
    byte[] payload = AppSignatures.punchPayload(fields);

    assertThrows(IllegalArgumentException.class, () -> AppSignatures.parseP256PublicKey(spki(p384)));
    assertFalse(AppSignatures.verify(spki(p384), payload, sign(p384, payload)));
  }

  @Property(tries = 100)
  void theSameFieldsAlwaysGiveTheSameBytesAndDifferentFieldsDifferentBytes(
      @ForAll("punches") PunchFields a, @ForAll("punches") PunchFields b) {
    assertTrue(Arrays.equals(AppSignatures.punchPayload(a), AppSignatures.punchPayload(a)));
    boolean sameFields = a.equals(b);
    assertEquals(
        sameFields, Arrays.equals(AppSignatures.punchPayload(a), AppSignatures.punchPayload(b)));
  }

  // --- Property 20: The punch payload never carries coordinates ---

  @Property(tries = 200)
  void thePunchPayloadHasExactlyTheTenDeclaredKeysAndNoCoordinateField(
      @ForAll("punches") PunchFields fields,
      @ForAll("latitudes") double lat,
      @ForAll("longitudes") double lng) {
    String text = new String(AppSignatures.punchPayload(fields), StandardCharsets.UTF_8);
    String[] lines = text.split("\n", -1);

    assertEquals(AppSignatures.PUNCH_VERSION, lines[0]);
    assertEquals(11, lines.length, "version line + ten fields, no trailing newline");
    assertFalse(text.endsWith("\n"));

    List<String> keys = new ArrayList<>();
    for (int i = 1; i < lines.length; i++) {
      String line = lines[i];
      int eq = line.indexOf('=');
      assertTrue(eq > 0, "line without key=: " + line);
      String key = line.substring(0, eq);
      String value = line.substring(eq + 1);
      keys.add(key);
      String lower = key.toLowerCase(Locale.ROOT);
      assertFalse(
          lower.equals("lat") || lower.equals("lng") || lower.equals("latitude")
              || lower.equals("longitude") || lower.contains("coord"),
          "coordinate field in payload: " + key);
      assertFalse(value.contains("\n") || value.contains("\r"));
    }
    assertEquals(PUNCH_KEYS, keys, "fixed key set in fixed order");

    // no decimal number anywhere: a coordinate cannot hide in any value
    assertFalse(DECIMAL_NUMBER.matcher(text).find(), "decimal number in payload: " + text);
    assertEquals(-1, text.indexOf('.'), "no decimal point at all");

    // the text of any plausible coordinate is absent, however it would be printed
    for (double coordinate : new double[] {lat, lng}) {
      assertFalse(text.contains(Double.toString(coordinate)));
      assertFalse(text.contains(String.format(Locale.ROOT, "%.6f", coordinate)));
      assertFalse(text.contains(String.format(Locale.ROOT, "%.4f", coordinate)));
    }

    // the only free integers are the two whole-metre fields, exactly as given
    assertEquals(
        fields.distanceMetres() == null ? AppSignatures.NO_VALUE : fields.distanceMetres().toString(),
        lines[8].substring("distanceMetres=".length()));
    assertEquals(
        fields.accuracyMetres() == null ? AppSignatures.NO_VALUE : fields.accuracyMetres().toString(),
        lines[9].substring("accuracyMetres=".length()));
    for (int i = 6; i <= 10; i++) {
      if (i == 8 || i == 9) {
        continue;
      }
      String value = lines[i].substring(lines[i].indexOf('=') + 1);
      assertTrue(value.equals("0") || value.equals("1"), "flag not a bit: " + lines[i]);
    }
  }
}
