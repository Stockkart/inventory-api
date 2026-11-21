package com.inventory.hr.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.inventory.common.constants.ErrorCode;
import com.inventory.common.exception.BaseException;
import com.inventory.hr.domain.model.AppHealthAlert;
import com.inventory.hr.domain.model.enums.HrPermission;
import com.inventory.hr.domain.repository.AppHealthAlertRepository;
import com.inventory.hr.rest.dto.response.AppHealthResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * The signature-failure window and alert (R10.4–R10.6): buckets roll out of the hour, an alert
 * is raised once when more than 1 % of at least 20 attempts failed, never with fewer than 20,
 * clears after a full clean window, recalculation is idempotent, and feeding never throws.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AppPunchHealthServiceTest {

  private static final String SHOP = "65f000000000000000000b01";
  private static final String OTHER_SHOP = "65f000000000000000000b02";
  private static final String USER = "u1";
  private static final Instant START = Instant.parse("2025-04-03T09:00:00Z");

  @Mock private AppHealthAlertRepository alerts;
  @Mock private HrPermissionGuard guard;

  private MutableClock clock;
  private AppPunchHealthService service;
  /** What the repository "holds": saved rows, so reads after a save see it. */
  private final List<AppHealthAlert> stored = new ArrayList<>();

  @BeforeEach
  void setUp() {
    clock = new MutableClock(START);
    service = new AppPunchHealthService(alerts, guard, clock);
    when(alerts.save(any())).thenAnswer(inv -> {
      AppHealthAlert a = inv.getArgument(0);
      if (a.getId() == null) {
        a.setId("alert-" + (stored.size() + 1));
        stored.add(a);
      }
      return a;
    });
    when(alerts.findByClearedAtIsNull())
        .thenAnswer(inv -> stored.stream().filter(a -> a.getClearedAt() == null).toList());
    when(alerts.findByShopIdAndClearedAtIsNull(anyString())).thenAnswer(inv -> stored.stream()
        .filter(a -> a.getClearedAt() == null && inv.getArgument(0).equals(a.getShopId()))
        .findFirst());
  }

  private void feed(String shop, int attempts, int failures) {
    for (int i = 0; i < attempts; i++) {
      service.record(shop, i < failures);
    }
  }

  /** Runs the service past its own start so a window can be "full". */
  private void warmUp() {
    clock.advance(Duration.ofMinutes(60));
  }

  // ---- buckets ---------------------------------------------------------------------------------

  @Test
  void bucketsRollOutOfTheWindowAfterSixtyMinutes() {
    feed(SHOP, 10, 2);
    assertEquals(new AppPunchHealthService.Totals(10, 2), service.totals(SHOP));

    clock.advance(Duration.ofMinutes(59));
    feed(SHOP, 5, 0);
    assertEquals(new AppPunchHealthService.Totals(15, 2), service.totals(SHOP));

    clock.advance(Duration.ofMinutes(1));
    assertEquals(new AppPunchHealthService.Totals(5, 0), service.totals(SHOP),
        "the first minute is now 60 minutes old and out of the window");

    clock.advance(Duration.ofMinutes(59));
    assertEquals(new AppPunchHealthService.Totals(0, 0), service.totals(SHOP));
  }

  @Test
  void aBucketReusedAnHourLaterStartsEmpty() {
    feed(SHOP, 7, 7);
    clock.advance(Duration.ofMinutes(60));
    feed(SHOP, 1, 0);
    assertEquals(new AppPunchHealthService.Totals(1, 0), service.totals(SHOP));
  }

  @Test
  void shopsAreKeptApartAndIdleShopsAreDropped() {
    feed(SHOP, 3, 0);
    feed(OTHER_SHOP, 4, 1);
    assertEquals(new AppPunchHealthService.Totals(3, 0), service.totals(SHOP));
    assertEquals(new AppPunchHealthService.Totals(4, 1), service.totals(OTHER_SHOP));
    assertEquals(2, service.size());

    clock.advance(Duration.ofMinutes(60));
    service.recalculate();
    assertEquals(0, service.size());
  }

  // ---- raising ---------------------------------------------------------------------------------

  @Test
  void rateAboveOnePercentWithTwentyAttemptsRaisesOnce() {
    feed(SHOP, 20, 1); // 5 %
    service.recalculate();
    service.recalculate();
    clock.advance(Duration.ofMinutes(5));
    service.recalculate();

    verify(alerts, times(1)).save(any());
    assertEquals(1, stored.size());
    AppHealthAlert a = stored.get(0);
    assertEquals(SHOP, a.getShopId());
    assertEquals(0.05, a.getRate(), 1e-9);
    assertEquals(1, a.getFailed());
    assertEquals(20, a.getAttempts());
    assertEquals(START, a.getRaisedAt());
    assertNull(a.getClearedAt());
  }

  @Test
  void fewerThanTwentyAttemptsNeverRaise() {
    feed(SHOP, 19, 19); // 100 %, but too few
    service.recalculate();
    verify(alerts, never()).save(any());
  }

  @Test
  void exactlyOnePercentDoesNotRaise() {
    feed(SHOP, 100, 1); // 1 % exactly: at or below the line
    service.recalculate();
    verify(alerts, never()).save(any());
  }

  @Test
  void onlyShopsOverTheLineAreAlerted() {
    feed(SHOP, 50, 0);
    feed(OTHER_SHOP, 50, 2); // 4 %
    service.recalculate();
    assertEquals(1, stored.size());
    assertEquals(OTHER_SHOP, stored.get(0).getShopId());
  }

  @Test
  void failuresFromAnHourAgoNoLongerRaise() {
    feed(SHOP, 20, 5);
    clock.advance(Duration.ofMinutes(60));
    feed(SHOP, 20, 0);
    service.recalculate();
    verify(alerts, never()).save(any());
  }

  // ---- clearing --------------------------------------------------------------------------------

  @Test
  void clearsOnlyAfterAFullCleanWindow() {
    warmUp();
    feed(SHOP, 20, 1);
    service.recalculate();
    AppHealthAlert a = stored.get(0);
    Instant raisedAt = a.getRaisedAt();

    // 30 minutes later the window is diluted below 1 % but is not a full window since the raise.
    clock.advance(Duration.ofMinutes(30));
    feed(SHOP, 200, 0);
    service.recalculate();
    assertNull(a.getClearedAt(), "half a window is not enough");

    // 59 minutes after the raise: still not a full window.
    clock.advance(Duration.ofMinutes(29));
    service.recalculate();
    assertNull(a.getClearedAt());

    // 60 minutes after the raise with a clean window: cleared, once.
    clock.advance(Duration.ofMinutes(1));
    service.recalculate();
    assertEquals(raisedAt.plus(Duration.ofMinutes(60)), a.getClearedAt());
    service.recalculate();
    verify(alerts, times(2)).save(any()); // one raise, one clear
  }

  @Test
  void doesNotClearWhileTheWindowIsStillOverTheLine() {
    warmUp();
    feed(SHOP, 20, 1);
    service.recalculate();
    AppHealthAlert a = stored.get(0);

    clock.advance(Duration.ofMinutes(60));
    feed(SHOP, 20, 1); // fresh failures at 5 %
    service.recalculate();
    assertNull(a.getClearedAt());

    clock.advance(Duration.ofMinutes(60));
    service.recalculate();
    assertNotNull(a.getClearedAt(), "the shop went quiet for a full window");
  }

  @Test
  void aQuietShopWithAnOldAlertClearsOnceTheInstanceHasObservedAFullWindow() {
    // An alert raised long ago (another instance, before a restart): no buckets in memory.
    AppHealthAlert old = new AppHealthAlert("alert-old", SHOP, 0.2, 4, 20,
        START.minus(Duration.ofHours(5)), null);
    stored.add(old);

    service.recalculate();
    assertNull(old.getClearedAt(), "right after start the window is not full");

    clock.advance(Duration.ofMinutes(59));
    service.recalculate();
    assertNull(old.getClearedAt());

    clock.advance(Duration.ofMinutes(1));
    service.recalculate();
    assertNotNull(old.getClearedAt());
  }

  @Test
  void anOpenAlertIsNeverRaisedTwiceEvenWhenTheRateStaysHigh() {
    feed(SHOP, 40, 10);
    service.recalculate();
    clock.advance(Duration.ofMinutes(5));
    feed(SHOP, 40, 10);
    service.recalculate();
    assertEquals(1, stored.size());
  }

  // ---- robustness ------------------------------------------------------------------------------

  @Test
  void recordNeverThrows() {
    service.record(null, true);
    service.record("", false);
    service.record("   ", true);
    assertEquals(0, service.size());
    // a shop id of any shape is accepted
    service.record("not-an-object-id", true);
    assertEquals(new AppPunchHealthService.Totals(1, 1), service.totals("not-an-object-id"));
  }

  @Test
  void recalculateSurvivesARepositoryFailure() {
    feed(SHOP, 20, 5);
    doThrow(new RuntimeException("mongo down")).when(alerts).findByClearedAtIsNull();
    doThrow(new RuntimeException("mongo down")).when(alerts).save(any());
    service.recalculate(); // no exception
    assertEquals(new AppPunchHealthService.Totals(20, 5), service.totals(SHOP),
        "the window is untouched and the next run can still raise");
  }

  // ---- read ------------------------------------------------------------------------------------

  @Test
  void openAlertsRequiresViewAttendanceAndReturnsTheShopsOpenAlert() {
    stored.add(new AppHealthAlert("a1", SHOP, 0.05, 1, 20, START, null));
    stored.add(new AppHealthAlert("a2", SHOP, 0.5, 10, 20, START.minus(Duration.ofDays(1)),
        START.minus(Duration.ofHours(20))));
    stored.add(new AppHealthAlert("a3", OTHER_SHOP, 0.05, 1, 20, START, null));

    AppHealthResponse r = service.openAlerts(USER, SHOP);

    verify(guard).require(USER, SHOP, HrPermission.VIEW_ATTENDANCE);
    assertEquals(1, r.getAlerts().size());
    AppHealthResponse.Alert row = r.getAlerts().get(0);
    assertEquals(SHOP, row.getShopId());
    assertEquals(0.05, row.getRate(), 1e-9);
    assertEquals(1, row.getFailed());
    assertEquals(20, row.getAttempts());
    assertEquals(START, row.getRaisedAt());
  }

  @Test
  void openAlertsIsEmptyWhenNothingIsOpen() {
    assertTrue(service.openAlerts(USER, SHOP).getAlerts().isEmpty());
  }

  @Test
  void openAlertsRefusedWithoutViewAttendance() {
    when(guard.require(USER, SHOP, HrPermission.VIEW_ATTENDANCE))
        .thenThrow(new BaseException(ErrorCode.ACCESS_DENIED, HrPermissionGuard.DENIED));
    BaseException e = assertThrows(BaseException.class, () -> service.openAlerts(USER, SHOP));
    assertEquals(ErrorCode.ACCESS_DENIED, e.getErrorCode());
    verify(alerts, never()).findByShopIdAndClearedAtIsNull(anyString());
  }

  /** A clock the test moves forward. */
  static final class MutableClock extends Clock {
    private Instant now;

    MutableClock(Instant start) {
      this.now = start;
    }

    void advance(Duration d) {
      now = now.plus(d);
    }

    @Override
    public ZoneOffset getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }
}
