package com.inventory.app.heavy;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventory.common.heavy.HeavyKind;
import com.inventory.common.heavy.HeavyRequest;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Every report download, analytics screen and import endpoint carries {@link HeavyRequest} of the
 * right kind — so a new MIS / GSTR / analytics endpoint cannot skip the limit by accident.
 */
class HeavyRequestCoverageTest {

  /** Full path and HTTP verb → the kind it must have (null: must not be heavy). */
  private static HeavyKind expected(String verb, String path) {
    if (path.startsWith("/api/v1/mis/") && path.matches(".*/(pdf|excel)$")) {
      return HeavyKind.EXPORT;
    }
    if (path.matches("/api/v1/taxation/gstr[0-9a-z]+/download.*")) {
      return HeavyKind.EXPORT;
    }
    if (path.startsWith("/api/v1/analytics/")) {
      return HeavyKind.ANALYTICS;
    }
    if (path.equals("/api/v1/inventory/parse-stock-sheet") || path.equals("/api/v1/pricing/bulk-update")) {
      return HeavyKind.IMPORT;
    }
    // HR files and the month-wide payroll calculations (HR decision 9); GET /payroll-runs (the
    // list) shares its path with create, so the verb decides
    if (path.equals("/api/v1/hr/attendance/month/export") || path.equals("/api/v1/hr/leave-balances/export")
        || path.equals("/api/v1/hr/payroll-runs/{runId}/register")) {
      return HeavyKind.HR;
    }
    if ("POST".equals(verb) && (path.equals("/api/v1/hr/payroll-runs")
        || path.matches("/api/v1/hr/payroll-runs/\\{runId}/(recalculate|finalise)"))) {
      return HeavyKind.HR;
    }
    return null;
  }

  private static Map<String, Method> endpoints() throws ClassNotFoundException {
    ClassPathScanningCandidateComponentProvider scan = new ClassPathScanningCandidateComponentProvider(false);
    scan.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
    Map<String, Method> out = new LinkedHashMap<>();
    for (var bean : scan.findCandidateComponents("com.inventory")) {
      Class<?> type = Class.forName(bean.getBeanClassName());
      RequestMapping base = AnnotatedElementUtils.findMergedAnnotation(type, RequestMapping.class);
      String[] prefixes = base != null && base.path().length > 0 ? base.path() : new String[] {""};
      for (Method m : type.getDeclaredMethods()) {
        RequestMapping rm = AnnotatedElementUtils.findMergedAnnotation(m, RequestMapping.class);
        if (rm == null) {
          continue;
        }
        String[] paths = rm.path().length > 0 ? rm.path() : new String[] {""};
        String verb = rm.method().length > 0 ? rm.method()[0].name() : "ANY";
        for (String prefix : prefixes) {
          for (String p : paths) {
            out.put(verb + " " + (prefix + p).replaceAll("/+$", "") + " " + type.getSimpleName() + "#" + m.getName(), m);
          }
        }
      }
    }
    return out;
  }

  @Test
  void heavyEndpointsCarryTheRightKind() throws Exception {
    Map<String, Method> all = endpoints();
    List<String> wrong = new ArrayList<>();
    int heavy = 0;
    for (Map.Entry<String, Method> e : all.entrySet()) {
      String[] key = e.getKey().split(" ");
      HeavyKind want = expected(key[0], key[1]);
      HeavyRequest has = e.getValue().getAnnotation(HeavyRequest.class);
      HeavyKind got = has == null ? null : has.value();
      if (want != got) {
        wrong.add(e.getKey() + ": expected " + want + ", has " + got);
      }
      if (got != null) {
        heavy++;
      }
    }
    assertThat(wrong).isEmpty();
    assertThat(all).hasSizeGreaterThan(100); // the scan really saw the controllers
    assertThat(heavy).isEqualTo(26); // 12 exports, 6 analytics, 2 imports, 6 HR
  }
}
