package com.inventory.hr.utils;

import com.inventory.hr.domain.model.Employee.SalaryRevision;
import java.util.Comparator;
import java.util.List;

/** Which salary revision applies in a period (R4.12). YYYY-MM strings sort by time. */
public final class SalaryRevisionUtils {

  private SalaryRevisionUtils() {}

  /** Latest revision with {@code effectiveFrom <= period}; null when none applies yet. */
  public static SalaryRevision revisionFor(List<SalaryRevision> revisions, String period) {
    if (revisions == null) {
      return null;
    }
    return revisions.stream()
        .filter(r -> r.getEffectiveFrom() != null && r.getEffectiveFrom().compareTo(period) <= 0)
        .max(Comparator.comparing(SalaryRevision::getEffectiveFrom))
        .orElse(null);
  }

  /** Sorted by effectiveFrom, oldest first. */
  public static void sort(List<SalaryRevision> revisions) {
    revisions.sort(Comparator.comparing(SalaryRevision::getEffectiveFrom));
  }
}
