package com.inventory.common.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.core.util.Separators;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Generates and checks the shared phone fixture {@code attendance-protocol/fixtures/phones.json}
 * (worker-app Property 1): what a worker may type → the 10 digits {@link PhoneNumbers#normalise}
 * answers, or {@code null} when it refuses. The TypeScript twin in {@code
 * inventory-platform/platform/attendance-protocol/src/phone.ts} reads the same file and asserts
 * the same answers, which is what keeps sign-up, the profile edit and HR's possible-match on one
 * rule.
 *
 * <p>The file is committed under {@code core/common/src/test/resources/} and copied to {@code
 * inventory-platform/platform/attendance-protocol/fixtures/}. {@link
 * #committedFixtureMatchesTheJavaRule} fails when the committed file no longer matches the rule;
 * {@link #regeneratesTheFixtureWhenStale} then rewrites it (also forced with {@code
 * -Dphones.fixture.write=true}), after which the build is re-run and both copies committed. Same
 * pattern as HR-module's {@code CanonicalFixtureGeneratorTest}.
 */
class PhoneNumbersFixtureGeneratorTest {

  static final String RESOURCE = "attendance-protocol/fixtures/phones.json";
  static final String WRITE_PROPERTY = "phones.fixture.write";

  private final ObjectMapper json = new ObjectMapper();

  // --- cases ---

  record PhoneCase(String name, String input) {}

  /** Inputs only; the expected answers are computed by the rule so the file cannot disagree. */
  static List<PhoneCase> cases() {
    return List.of(
        new PhoneCase("bare 10 digits", "9876543210"),
        new PhoneCase("10 digits with a space in the middle", "98765 43210"),
        new PhoneCase("10 digits with hyphens", "98765-43210"),
        new PhoneCase("10 digits surrounded by spaces", "  9876543210  "),
        new PhoneCase("country code with plus", "+919876543210"),
        new PhoneCase("country code without plus", "919876543210"),
        new PhoneCase("plus, country code, space", "+91 98765 43210"),
        new PhoneCase("plus, country code, hyphens", "+91-98765-43210"),
        new PhoneCase("country code in brackets", "(91) 9876543210"),
        new PhoneCase("dots between groups", "98765.43210"),
        new PhoneCase("letters are dropped like formatting", "abc9876543210"),
        new PhoneCase("leading zero makes 11 digits", "09876543210"),
        new PhoneCase("0 then country code (13 digits)", "0919876543210"),
        new PhoneCase("nine digits", "987654321"),
        new PhoneCase("eleven digits not starting with 91", "19876543210"),
        new PhoneCase("twelve digits not starting with 91", "129876543210"),
        new PhoneCase("thirteen digits", "9198765432101"),
        new PhoneCase("empty", ""),
        new PhoneCase("spaces only", "   "),
        new PhoneCase("plus only", "+"),
        new PhoneCase("no digits", "+91-abc"),
        new PhoneCase("devanagari digits are not ASCII digits", "९८७६५४३२१०"),
        new PhoneCase("fullwidth digits are not ASCII digits", "９８７６５４３２１０"),
        new PhoneCase("all zeros", "0000000000"),
        new PhoneCase("91 as the first two of ten", "9198765432"),
        new PhoneCase("country code twice", "91919876543210"),
        new PhoneCase("plus 91 then nine digits", "+91 98765 4321"),
        new PhoneCase("other country code", "+44 7911 123456"));
  }

  // --- fixture document ---

  Map<String, Object> fixture() {
    Map<String, Object> root = new LinkedHashMap<>();
    root.put(
        "generatedBy",
        "inventory-api core/common com.inventory.common.util.PhoneNumbersFixtureGeneratorTest"
            + " (do not edit by hand)");
    root.put(
        "rule",
        "drop every character that is not an ASCII digit 0-9; accept iff exactly 10 digits remain,"
            + " or 12 starting with 91 (then drop the 91); answer the 10 digits, else null");
    root.put("localDigits", PhoneNumbers.LOCAL_DIGITS);
    root.put("countryCode", PhoneNumbers.COUNTRY_CODE);
    root.put("cases", cases().stream().map(PhoneNumbersFixtureGeneratorTest::entry).toList());
    return root;
  }

  private static Map<String, Object> entry(PhoneCase c) {
    Map<String, Object> e = new LinkedHashMap<>();
    e.put("name", c.name());
    e.put("input", c.input());
    e.put("normalised", PhoneNumbers.normalise(c.input()).orElse(null));
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
  void committedFixtureMatchesTheJavaRule() throws IOException {
    JsonNode committed = readCommitted();
    assertEquals(PhoneNumbers.LOCAL_DIGITS, committed.path("localDigits").asInt());
    assertEquals(PhoneNumbers.COUNTRY_CODE, committed.path("countryCode").asText());

    List<PhoneCase> cases = cases();
    assertEquals(cases.size(), committed.path("cases").size(), "case count");
    for (int i = 0; i < cases.size(); i++) {
      JsonNode node = committed.path("cases").get(i);
      PhoneCase c = cases.get(i);
      assertEquals(c.name(), node.path("name").asText(), "case name");
      assertEquals(c.input(), node.path("input").asText(), c.name() + ": input");
      String expected = PhoneNumbers.normalise(c.input()).orElse(null);
      JsonNode answer = node.path("normalised");
      assertEquals(expected, answer.isNull() ? null : answer.asText(), c.name() + ": normalised");
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
    assertEquals(cases().size(), parsed.path("cases").size());
    if (stale && !forced) {
      fail(
          "Fixture was stale and has been regenerated at "
              + target
              + ". Re-run the build so the new file is on the classpath, then commit it and copy"
              + " it to inventory-platform/platform/attendance-protocol/fixtures/phones.json.");
    }
  }

  @Test
  void renderingIsDeterministic() throws IOException {
    assertEquals(render(), render());
    assertTrue(render().endsWith("}\n"));
  }

  @Test
  void fixtureCoversBothAnswers() {
    List<Map<String, Object>> entries = cases().stream().map(PhoneNumbersFixtureGeneratorTest::entry).toList();
    assertTrue(entries.stream().anyMatch(e -> e.get("normalised") != null), "an accepted case");
    assertTrue(entries.stream().anyMatch(e -> e.get("normalised") == null), "a refused case");
    assertEquals(
        cases().size(),
        cases().stream().map(PhoneCase::name).distinct().count(),
        "case names are unique");
  }

  // --- helpers ---

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

  /** {@code core/common/src/test/resources/<RESOURCE>} whether run from the module or the repo root. */
  static Path sourcePath() {
    Path base = Path.of(System.getProperty("basedir", System.getProperty("user.dir")));
    if (!Files.exists(base.resolve("pom.xml"))
        || !Files.exists(base.resolve("src/main/java/com/inventory/common"))) {
      Path fromRoot = base.resolve("core/common");
      if (Files.exists(fromRoot.resolve("pom.xml"))) {
        base = fromRoot;
      }
    }
    return base.resolve("src/test/resources").resolve(RESOURCE);
  }
}
