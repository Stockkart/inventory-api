package com.inventory.app.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Guards {@code grafana/dashboards/03-module.json} (HR-module R10.7–R10.8): the file parses, the
 * Attendance app adds exactly three panels that all filter on the {@code shop} variable, and the
 * panels that were there before keep their ids and never mention the new variable. The task 16.5
 * polish (signature-failure threshold line, per-shop backlog table, metric-doc links) lives inside
 * those same three panels, so the panel set does not grow.
 *
 * <p>Stockkart Team (worker-app R14.3, task 7.2) adds exactly one more panel, "Join
 * requests per event" (id 203), on the same {@code shop} variable; every other panel is unchanged.
 */
class DashboardJsonTest {

  /** Panel ids of the dashboard before the Attendance app panels were added. */
  private static final Set<Integer> PRE_EXISTING_PANEL_IDS =
      Set.of(1, 2, 3, 4, 5, 8, 99, 100, 101, 102, 104, 105, 106, 107, 6, 7);

  private static final Set<Integer> ATTENDANCE_APP_PANEL_IDS = Set.of(200, 201, 202);

  /** The one panel the Stockkart Team app adds (worker-app R14.3). */
  private static final int JOIN_REQUESTS_PANEL_ID = 203;

  private static JsonNode dashboard;

  @BeforeAll
  static void parse() throws IOException {
    Path file = findRepoRoot().resolve("grafana/dashboards/03-module.json");
    assertTrue(Files.exists(file), "missing " + file);
    dashboard = new ObjectMapper().readTree(Files.readString(file));
    assertNotNull(dashboard);
    assertEquals("inventory-api-03-module", dashboard.path("uid").asText());
  }

  @Test
  void exactlyThreeAttendanceAppPanelsEachFilteredByShop() {
    List<JsonNode> panels = allPanels();
    List<JsonNode> added = panels.stream()
        .filter(p -> ATTENDANCE_APP_PANEL_IDS.contains(p.path("id").asInt())).toList();
    assertEquals(3, added.size(), "three Attendance app panels");

    assertPanel(panelById(200), "Attendance app punches per result", 2,
        "sum by (result) (rate(inventory_hr_app_punches_total{", "[5m]))");
    assertPanel(panelById(201), "Attendance app offline backlog", 2,
        "sum(increase(inventory_hr_app_offline_backlog_total{", "[1h]))");
    assertPanel(panelById(202), "Attendance app pairing failures per reason", 1,
        "sum by (reason) (rate(inventory_hr_app_pairings_total{event=\"failed\"", "[5m]))");
  }

  /**
   * Task 16.5 polish on panel 200: query B is the R10.4 signature failure rate (signature_failed
   * over all attempts, rolling 1 h, per shop) and its override draws the 1 % threshold as a line.
   */
  @Test
  void punchesPanelCarriesTheSignatureFailureRateWithAOnePercentThresholdLine() {
    JsonNode panel = panelById(200);
    String rate = panel.path("targets").get(1).path("expr").asText();
    assertTrue(rate.contains("result=\"signature_failed\""), rate);
    assertTrue(rate.contains("[1h]"), rate);
    assertTrue(rate.contains(" / sum by (shop) (rate(inventory_hr_app_punches_total{"), rate);
    assertTrue(rate.contains("shop=~\"$shop\""), rate);

    JsonNode override = overrideFor(panel, "B");
    assertEquals("percentunit", property(override, "unit").asText());
    assertEquals("line", property(override, "custom.thresholdsStyle").path("mode").asText());
    JsonNode steps = property(override, "thresholds").path("steps");
    boolean onePercent = false;
    for (JsonNode step : steps) {
      onePercent |= step.path("value").isNumber() && step.path("value").asDouble() == 0.01;
    }
    assertTrue(onePercent, "a threshold step at 0.01 (1 %): " + steps);
  }

  /** Task 16.5 polish on panel 201: query B breaks the backlog down per shop, legend as a table. */
  @Test
  void backlogPanelBreaksDownPerShopInALegendTable() {
    JsonNode panel = panelById(201);
    String perShop = panel.path("targets").get(1).path("expr").asText();
    assertTrue(perShop.startsWith("sum by (shop) (increase(inventory_hr_app_offline_backlog_total{"),
        perShop);
    assertTrue(perShop.endsWith("[1h]))"), perShop);
    JsonNode legend = panel.path("options").path("legend");
    assertEquals("table", legend.path("displayMode").asText());
    assertTrue(legend.path("calcs").toString().contains("lastNotNull"), legend.toString());
  }

  /** Task 16.5: every Attendance app panel description points at the metric rows. */
  @Test
  void attendanceAppPanelDescriptionsLinkTheMetricsDoc() {
    for (int id : ATTENDANCE_APP_PANEL_IDS) {
      String description = panelById(id).path("description").asText();
      assertTrue(description.contains("docs/metrics.md"), "panel " + id + ": " + description);
      assertTrue(description.contains("https://github.com/Stockkart/inventory-api/blob/main/docs/metrics.md"),
          "panel " + id + " must carry an absolute link Grafana can open: " + description);
    }
  }

  /**
   * Worker-app R14.3 (task 7.2): exactly one panel "Join requests per event", one query summing
   * the join-request counter by event over an hour (no {@code kind} tag since D11), on the existing
   * shop variable and the service/env matchers every other panel carries, with the metrics-doc link.
   */
  @Test
  void exactlyOneJoinRequestsPanelOnTheShopVariable() {
    List<JsonNode> byTitle = allPanels().stream()
        .filter(p -> "Join requests per event".equals(p.path("title").asText())).toList();
    assertEquals(1, byTitle.size(), "one 'Join requests per event' panel");
    JsonNode panel = byTitle.get(0);
    assertEquals(JOIN_REQUESTS_PANEL_ID, panel.path("id").asInt());

    assertPanel(panel, "Join requests per event", 1,
        "sum by (event) (increase(inventory_hr_worker_join_requests_total{", "[1h]))");
    String expr = panel.path("targets").get(0).path("expr").asText();
    assertEquals(
        "sum by (event) (increase(inventory_hr_worker_join_requests_total{"
            + "service=\"inventory-api\", env=\"$env\", shop=~\"$shop\"}[1h]))",
        expr);
    assertEquals("{{event}}", panel.path("targets").get(0).path("legendFormat").asText());

    String description = panel.path("description").asText();
    assertTrue(description.contains(
            "https://github.com/Stockkart/inventory-api/blob/main/docs/metrics.md#hr"),
        "panel 203 must link the HR metric rows: " + description);
  }

  @Test
  void theShopVariableExistsAndIsFedByThePunchCounter() {
    JsonNode shop = null;
    for (JsonNode v : dashboard.path("templating").path("list")) {
      if ("shop".equals(v.path("name").asText())) {
        shop = v;
      }
    }
    assertNotNull(shop, "templating variable 'shop'");
    assertTrue(shop.path("includeAll").asBoolean());
    assertTrue(shop.path("multi").asBoolean());
    assertTrue(shop.path("query").asText().contains("inventory_hr_app_punches_total"));
    assertTrue(shop.path("query").asText().endsWith(", shop)"));
  }

  @Test
  void preExistingPanelsKeepTheirIdsAndNeverUseTheShopVariable() {
    Set<Integer> ids = new TreeSet<>();
    for (JsonNode p : allPanels()) {
      int id = p.path("id").asInt();
      ids.add(id);
      if (ATTENDANCE_APP_PANEL_IDS.contains(id) || id == JOIN_REQUESTS_PANEL_ID) {
        continue;
      }
      assertTrue(PRE_EXISTING_PANEL_IDS.contains(id), "unexpected panel id " + id);
      assertFalse(p.toString().contains("$shop"),
          "pre-existing panel " + id + " must not reference the shop variable");
    }
    Set<Integer> expected = new TreeSet<>(PRE_EXISTING_PANEL_IDS);
    expected.addAll(ATTENDANCE_APP_PANEL_IDS);
    expected.add(JOIN_REQUESTS_PANEL_ID);
    assertEquals(expected, ids,
        "panel set = the old panels, the three Attendance app panels and the join-requests panel");
  }

  /**
   * Query A is the panel's defining expression (task 7.6); every query, including the task 16.5
   * additions, filters on the shop variable.
   */
  private static void assertPanel(JsonNode panel, String title, int targetCount,
      String exprPrefix, String exprSuffix) {
    assertEquals(title, panel.path("title").asText());
    assertEquals("timeseries", panel.path("type").asText());
    JsonNode targets = panel.path("targets");
    assertEquals(targetCount, targets.size(), title + ": queries");
    String expr = targets.get(0).path("expr").asText();
    assertTrue(expr.startsWith(exprPrefix), title + ": " + expr);
    assertTrue(expr.endsWith(exprSuffix), title + ": " + expr);
    for (JsonNode target : targets) {
      String each = target.path("expr").asText();
      assertTrue(each.contains("shop=~\"$shop\""), title + " must filter on $shop: " + each);
    }
  }

  private static JsonNode overrideFor(JsonNode panel, String refId) {
    for (JsonNode override : panel.path("fieldConfig").path("overrides")) {
      JsonNode matcher = override.path("matcher");
      if ("byFrameRefID".equals(matcher.path("id").asText())
          && refId.equals(matcher.path("options").asText())) {
        return override;
      }
    }
    throw new AssertionError("no override for refId " + refId);
  }

  private static JsonNode property(JsonNode override, String id) {
    for (JsonNode property : override.path("properties")) {
      if (id.equals(property.path("id").asText())) {
        return property.path("value");
      }
    }
    throw new AssertionError("no override property " + id);
  }

  private static JsonNode panelById(int id) {
    return allPanels().stream().filter(p -> p.path("id").asInt() == id).findFirst()
        .orElseThrow(() -> new AssertionError("no panel " + id));
  }

  /** Top-level panels and the panels nested inside collapsed rows. */
  private static List<JsonNode> allPanels() {
    List<JsonNode> out = new ArrayList<>();
    for (JsonNode p : dashboard.path("panels")) {
      out.add(p);
      for (JsonNode nested : p.path("panels")) {
        out.add(nested);
      }
    }
    return out;
  }

  private static Path findRepoRoot() {
    Path p = Path.of(System.getProperty("user.dir")).toAbsolutePath();
    while (p != null && !Files.exists(p.resolve("module-boundaries.yml"))) {
      p = p.getParent();
    }
    if (p == null) {
      throw new IllegalStateException(
          "module-boundaries.yml not found above " + System.getProperty("user.dir"));
    }
    return p;
  }
}
