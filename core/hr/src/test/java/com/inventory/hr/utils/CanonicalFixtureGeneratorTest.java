package com.inventory.hr.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.core.util.Separators;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.inventory.hr.utils.AppSignatures.EnvelopeFields;
import com.inventory.hr.utils.AppSignatures.PunchFields;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Generates and checks the shared canonical fixture {@code
 * attendance-protocol/fixtures/canonical.json} (design DD3): inputs → the exact bytes {@link
 * AppSignatures} signs or verifies. The TypeScript builder in {@code
 * inventory-platform/platform/attendance-protocol} reads the same file and asserts byte equality,
 * which is what keeps the two builders identical.
 *
 * <p>The file is committed under {@code core/hr/src/test/resources/}. {@link
 * #committedFixtureMatchesTheJavaBuilders} fails when the committed file no longer matches the
 * builders; {@link #regeneratesTheFixtureWhenStale} then rewrites it from the builders (also forced
 * with {@code -Dcanonical.fixture.write=true}), after which the build is re-run and the file
 * committed.
 */
class CanonicalFixtureGeneratorTest {

  static final String RESOURCE = "attendance-protocol/fixtures/canonical.json";
  static final String WRITE_PROPERTY = "canonical.fixture.write";

  private static final HexFormat HEX = HexFormat.of();

  private static final String PAIRING_ID = "67a1b2c3d4e5f60718293a4b";
  private static final String SHOP_ID = "5f1d7c9e8b3a4f2e1d0c9b8a";
  private static final String EMPLOYEE_ID = "64f0e1d2c3b4a59687766554";

  private final ObjectMapper json = new ObjectMapper();

  // --- cases ---

  record PunchCase(String name, PunchFields fields) {}

  record EnvelopeCase(String name, EnvelopeFields fields) {}

  static List<PunchCase> punchCases() {
    return List.of(
        new PunchCase(
            "online, location checked, inside shop",
            new PunchFields(
                PAIRING_ID,
                SHOP_ID,
                EMPLOYEE_ID,
                "3f2504e0-4f89-41d3-9a0c-0305e82c3301",
                Instant.parse("2025-04-03T03:42:07Z"),
                false,
                true,
                42,
                12,
                false)),
        new PunchCase(
            "online, location check off (no metres)",
            new PunchFields(
                PAIRING_ID,
                SHOP_ID,
                EMPLOYEE_ID,
                "9b2d1a3c-7e4f-4b8a-9c6d-1e2f3a4b5c6d",
                Instant.parse("2025-04-03T12:35:00Z"),
                false,
                false,
                null,
                null,
                false)),
        new PunchCase(
            "offline, location checked, no location obtained",
            new PunchFields(
                PAIRING_ID,
                SHOP_ID,
                EMPLOYEE_ID,
                "00000000-0000-4000-8000-000000000001",
                Instant.parse("2025-04-02T18:30:59Z"),
                true,
                true,
                null,
                null,
                false)),
        new PunchCase(
            "offline, mock location with metres present",
            new PunchFields(
                PAIRING_ID,
                SHOP_ID,
                EMPLOYEE_ID,
                "ffffffff-ffff-4fff-bfff-ffffffffffff",
                Instant.parse("2025-12-31T23:59:59Z"),
                true,
                true,
                1250,
                300,
                true)),
        new PunchCase(
            "online, zero distance and accuracy",
            new PunchFields(
                PAIRING_ID,
                SHOP_ID,
                EMPLOYEE_ID,
                "123e4567-e89b-42d3-a456-426614174000",
                Instant.parse("2024-02-29T00:00:00Z"),
                false,
                true,
                0,
                0,
                false)),
        new PunchCase(
            "online, large metres values",
            new PunchFields(
                PAIRING_ID,
                SHOP_ID,
                EMPLOYEE_ID,
                "6ba7b810-9dad-41d1-80b4-00c04fd430c8",
                Instant.parse("2025-07-15T06:05:04Z"),
                false,
                true,
                2000000000,
                65535,
                false)));
  }

  static List<EnvelopeCase> envelopeCases() {
    String punchBody =
        "{\"pairingId\":\""
            + PAIRING_ID
            + "\",\"shopId\":\""
            + SHOP_ID
            + "\",\"employeeId\":\""
            + EMPLOYEE_ID
            + "\",\"punchId\":\"3f2504e0-4f89-41d3-9a0c-0305e82c3301\","
            + "\"phoneTime\":\"2025-04-03T03:42:07Z\",\"offline\":false,\"locationChecked\":true,"
            + "\"distanceMetres\":42,\"accuracyMetres\":12,\"mock\":false,"
            + "\"signature\":\"MEUCIQD/placeholder/not/a/real/signature=\"}";
    return List.of(
        new EnvelopeCase(
            "POST punch with JSON body",
            new EnvelopeFields(
                PAIRING_ID,
                "POST",
                "/api/v1/hr/attendance-app/punches",
                null,
                1743651727L,
                "a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d",
                punchBody.getBytes(StandardCharsets.UTF_8))),
        new EnvelopeCase(
            "GET status, no query, no body",
            new EnvelopeFields(
                PAIRING_ID,
                "GET",
                "/api/v1/hr/attendance-app/me",
                "",
                1743651700L,
                "0f0e0d0c-0b0a-4908-8706-050403020100",
                null)),
        new EnvelopeCase(
            "GET history with query string",
            new EnvelopeFields(
                PAIRING_ID,
                "GET",
                "/api/v1/hr/attendance-app/punches",
                "from=2025-03-04&to=2025-04-03",
                1743651800L,
                "7c9e6679-7425-40de-944b-e07fc1f90ae7",
                null)),
        new EnvelopeCase(
            "POST unpair, no body",
            new EnvelopeFields(
                PAIRING_ID,
                "POST",
                "/api/v1/hr/attendance-app/pairings/me/unpair",
                null,
                1743651900L,
                "16fd2706-8baf-433b-82eb-8c7fada847da",
                new byte[0])),
        new EnvelopeCase(
            "POST consent with non-ASCII body",
            new EnvelopeFields(
                PAIRING_ID,
                "POST",
                "/api/v1/hr/attendance-app/consent",
                null,
                1743652000L,
                "550e8400-e29b-41d4-a716-446655440000",
                "{\"version\":2,\"note\":\"हाँ — I agree\"}".getBytes(StandardCharsets.UTF_8))));
  }

  // --- fixture document ---

  Map<String, Object> fixture() {
    Map<String, Object> root = new LinkedHashMap<>();
    root.put(
        "generatedBy",
        "inventory-api core/hr com.inventory.hr.utils.CanonicalFixtureGeneratorTest (do not edit by hand)");
    root.put("punchVersion", AppSignatures.PUNCH_VERSION);
    root.put("envelopeVersion", AppSignatures.ENVELOPE_VERSION);
    root.put("noValue", AppSignatures.NO_VALUE);
    root.put("punches", punchCases().stream().map(this::punchEntry).toList());
    root.put("envelopes", envelopeCases().stream().map(this::envelopeEntry).toList());
    return root;
  }

  private Map<String, Object> punchEntry(PunchCase c) {
    PunchFields f = c.fields();
    Map<String, Object> input = new LinkedHashMap<>();
    input.put("pairingId", f.pairingId());
    input.put("shopId", f.shopId());
    input.put("employeeId", f.employeeId());
    input.put("punchId", f.punchId());
    input.put("phoneTime", f.phoneTime().toString());
    input.put("offline", f.offline());
    input.put("locationChecked", f.locationChecked());
    input.put("distanceMetres", f.distanceMetres());
    input.put("accuracyMetres", f.accuracyMetres());
    input.put("mock", f.mock());
    byte[] canonical = AppSignatures.punchPayload(f);
    return entry(c.name(), input, canonical, Map.of());
  }

  private Map<String, Object> envelopeEntry(EnvelopeCase c) {
    EnvelopeFields f = c.fields();
    byte[] body = f.body() == null ? new byte[0] : f.body();
    Map<String, Object> input = new LinkedHashMap<>();
    input.put("pairingId", f.pairingId());
    input.put("method", f.method());
    input.put("path", f.path());
    input.put("query", f.query() == null ? "" : f.query());
    input.put("timestamp", f.timestamp());
    input.put("nonce", f.nonce());
    input.put("bodyUtf8", new String(body, StandardCharsets.UTF_8));
    input.put("bodyHex", HEX.formatHex(body));
    byte[] canonical = AppSignatures.requestEnvelope(f);
    return entry(c.name(), input, canonical, Map.of("bodySha256", AppSignatures.sha256Hex(body)));
  }

  private static Map<String, Object> entry(
      String name, Map<String, Object> input, byte[] canonical, Map<String, Object> extra) {
    Map<String, Object> e = new LinkedHashMap<>();
    e.put("name", name);
    e.put("input", input);
    e.putAll(extra);
    e.put("canonical", new String(canonical, StandardCharsets.UTF_8));
    e.put("canonicalHex", HEX.formatHex(canonical));
    e.put("canonicalSha256", AppSignatures.sha256Hex(canonical));
    return e;
  }

  String render() throws IOException {
    DefaultPrettyPrinter printer =
        new DefaultPrettyPrinter()
            .withObjectIndenter(new DefaultIndenter("  ", "\n"))
            .withArrayIndenter(new DefaultIndenter("  ", "\n"))
            .withSeparators(
                Separators.createDefaultInstance()
                    .withObjectFieldValueSpacing(Separators.Spacing.AFTER));
    return json.writer(printer).writeValueAsString(fixture()) + "\n";
  }

  // --- tests ---

  @Test
  void committedFixtureMatchesTheJavaBuilders() throws IOException {
    JsonNode committed = readCommitted();
    assertEquals(AppSignatures.PUNCH_VERSION, committed.path("punchVersion").asText());
    assertEquals(AppSignatures.ENVELOPE_VERSION, committed.path("envelopeVersion").asText());

    List<PunchCase> punches = punchCases();
    assertEquals(punches.size(), committed.path("punches").size(), "punch case count");
    for (int i = 0; i < punches.size(); i++) {
      JsonNode node = committed.path("punches").get(i);
      byte[] canonical = AppSignatures.punchPayload(punches.get(i).fields());
      assertCanonical(punches.get(i).name(), node, canonical);
    }

    List<EnvelopeCase> envelopes = envelopeCases();
    assertEquals(envelopes.size(), committed.path("envelopes").size(), "envelope case count");
    for (int i = 0; i < envelopes.size(); i++) {
      JsonNode node = committed.path("envelopes").get(i);
      EnvelopeFields f = envelopes.get(i).fields();
      byte[] canonical = AppSignatures.requestEnvelope(f);
      assertCanonical(envelopes.get(i).name(), node, canonical);
      byte[] body = f.body() == null ? new byte[0] : f.body();
      assertEquals(
          AppSignatures.sha256Hex(body), node.path("bodySha256").asText(), "bodySha256");
      assertEquals(HEX.formatHex(body), node.path("input").path("bodyHex").asText(), "bodyHex");
    }

    assertEquals(render(), readCommittedText(), "committed fixture text is stale; regenerate");
  }

  @Test
  void regeneratesTheFixtureWhenStale() throws IOException {
    String rendered = render();
    Path target = sourcePath();
    boolean forced = Boolean.getBoolean(WRITE_PROPERTY);
    boolean stale = !Files.exists(target) || !rendered.equals(Files.readString(target));
    if (forced || stale) {
      Files.createDirectories(target.getParent());
      Files.writeString(target, rendered, StandardCharsets.UTF_8);
    }
    assertEquals(rendered, Files.readString(target, StandardCharsets.UTF_8));
    JsonNode parsed = json.readTree(rendered);
    assertEquals(punchCases().size(), parsed.path("punches").size());
    assertEquals(envelopeCases().size(), parsed.path("envelopes").size());
    if (stale && !forced) {
      fail(
          "Fixture was stale and has been regenerated at "
              + target
              + ". Re-run the build so the new file is on the classpath, then commit it.");
    }
  }

  @Test
  void renderingIsDeterministic() throws IOException {
    assertEquals(render(), render());
    assertTrue(render().endsWith("}\n"));
  }

  // --- helpers ---

  private static void assertCanonical(String name, JsonNode node, byte[] canonical) {
    assertNotNull(node, name);
    assertEquals(name, node.path("name").asText(), "case name");
    assertEquals(
        new String(canonical, StandardCharsets.UTF_8),
        node.path("canonical").asText(),
        name + ": canonical");
    assertEquals(HEX.formatHex(canonical), node.path("canonicalHex").asText(), name + ": hex");
    assertEquals(
        AppSignatures.sha256Hex(canonical),
        node.path("canonicalSha256").asText(),
        name + ": sha256");
  }

  private JsonNode readCommitted() throws IOException {
    return json.readTree(readCommittedText());
  }

  private String readCommittedText() throws IOException {
    try (InputStream in = getClass().getClassLoader().getResourceAsStream(RESOURCE)) {
      if (in == null) {
        fail(
            RESOURCE
                + " is not on the test classpath. Run regeneratesTheFixtureWhenStale (or the build"
                + " with -D"
                + WRITE_PROPERTY
                + "=true), re-run, and commit "
                + sourcePath());
      }
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  /** {@code core/hr/src/test/resources/<RESOURCE>} whether run from the module or the repo root. */
  static Path sourcePath() {
    Path base = Path.of(System.getProperty("basedir", System.getProperty("user.dir")));
    if (!Files.exists(base.resolve("pom.xml")) || !Files.exists(base.resolve("src/main/java/com/inventory/hr"))) {
      Path fromRoot = base.resolve("core/hr");
      if (Files.exists(fromRoot.resolve("pom.xml"))) {
        base = fromRoot;
      }
    }
    return base.resolve("src/test/resources").resolve(RESOURCE);
  }
}
