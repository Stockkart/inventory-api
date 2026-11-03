package com.inventory.hr.service;

import com.inventory.hr.domain.model.Punch;
import com.inventory.hr.utils.HrDateUtils;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Punches of one employee-day → what the day shows (R4.1–R4.4). Pure: no repository, clock or
 * Spring bean ({@code PunchDayDeriverProperties}).
 *
 * <ul>
 *   <li>Punches are taken in time order (input order does not matter); a punch less than 5
 *       minutes after the previous <em>counted</em> punch is a duplicate (R4.2), so consecutive
 *       counted punches are always at least 5 minutes apart.
 *   <li>In-time = first counted punch, out-time = last counted punch (null when only one), both
 *       {@code HH:mm} in Asia/Kolkata.
 *   <li>{@code punchCount} = number of counted punches. It never goes down when punches are added
 *       (keeping the earliest punch of each 5-minute run is the largest such set), which is what
 *       the derived-write race guard relies on (design D4).
 * </ul>
 */
public final class PunchDayDeriver {

  /** R4.2: a punch closer than this to the previous counted punch is a duplicate. */
  public static final Duration DUPLICATE_WINDOW = Duration.ofMinutes(5);

  private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");

  private PunchDayDeriver() {}

  /**
   * @param counted counted punches, oldest first
   * @param inTime HH:mm of the first counted punch; null when none
   * @param outTime HH:mm of the last counted punch; null when fewer than two
   * @param workedMinutes from in/out by the existing rule; null without an out-time
   * @param missingCheckOut one counted punch and the day is over (see {@link #isMissingCheckOut})
   */
  public record Derived(List<Punch> counted, String inTime, String outTime, Integer workedMinutes,
      int punchCount, boolean missingCheckOut) {

    public boolean isEmpty() {
      return counted.isEmpty();
    }
  }

  /**
   * @param punches the employee's punches of {@code date} (any order; nulls and punches without
   *     a time are ignored)
   * @param date the day, Asia/Kolkata
   * @param today today, Asia/Kolkata
   * @param now the time now, Asia/Kolkata
   * @param workEnd the employee's (else the shop's) work end; null = only a past day misses
   */
  public static Derived derive(List<Punch> punches, LocalDate date, LocalDate today,
      LocalTime now, LocalTime workEnd) {
    List<Punch> sorted = new ArrayList<>();
    if (punches != null) {
      punches.stream().filter(Objects::nonNull).filter(p -> p.getAt() != null)
          .forEach(sorted::add);
    }
    // id breaks ties so equal times give the same counted punch whatever the input order
    sorted.sort(Comparator.comparing(Punch::getAt)
        .thenComparing(p -> p.getId() == null ? "" : p.getId()));
    List<Punch> counted = new ArrayList<>();
    Instant lastCounted = null;
    for (Punch p : sorted) {
      if (lastCounted == null
          || Duration.between(lastCounted, p.getAt()).compareTo(DUPLICATE_WINDOW) >= 0) {
        counted.add(p);
        lastCounted = p.getAt();
      }
    }
    String in = counted.isEmpty() ? null : hhmm(counted.get(0).getAt());
    String out = counted.size() < 2 ? null : hhmm(counted.get(counted.size() - 1).getAt());
    Integer worked = DayStatusResolver.workedMinutes(in, out);
    return new Derived(List.copyOf(counted), in, out, worked, counted.size(),
        isMissingCheckOut(counted.size(), date, today, now, workEnd));
  }

  /**
   * R4.4: exactly one counted punch, and the day is over (a past date) or it is today at or after
   * the work end time.
   */
  public static boolean isMissingCheckOut(Integer punchCount, LocalDate date, LocalDate today,
      LocalTime now, LocalTime workEnd) {
    if (punchCount == null || punchCount != 1 || date == null || today == null) {
      return false;
    }
    if (date.isBefore(today)) {
      return true;
    }
    return date.equals(today) && workEnd != null && now != null && !now.isBefore(workEnd);
  }

  static String hhmm(Instant at) {
    return at.atZone(HrDateUtils.ZONE).toLocalTime().format(HH_MM);
  }
}
