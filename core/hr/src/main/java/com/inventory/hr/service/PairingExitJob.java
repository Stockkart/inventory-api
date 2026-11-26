package com.inventory.hr.service;

import com.inventory.hr.domain.model.DevicePairing;
import com.inventory.hr.domain.model.enums.PairingStatus;
import com.inventory.hr.domain.model.enums.UnpairReason;
import com.inventory.hr.domain.repository.DevicePairingRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Unpairs the phones of employees whose exit date has arrived (R3.4–R3.6).
 *
 * <p>The employee save writes {@code scheduledUnpairAt = exitDate 00:00 Asia/Kolkata} on the
 * active pairing ({@link DevicePairingService#scheduleExitUnpair}); once a minute this job takes
 * every {@code ACTIVE} pairing whose schedule is due and unpairs it as {@code EMPLOYEE_EXITED} by
 * {@code system}, which writes the audit row and the counter. The unpair is one conditional update
 * on {@code status = ACTIVE}, so the job is idempotent and harmless when two instances run it; a
 * pairing that fails is logged and picked up again on the next tick, since its schedule stays.
 *
 * <p>Listed under "Scheduled jobs" in the handbook's {@code IN_MEMORY_STATE.md}.
 */
@Slf4j
@Component
public class PairingExitJob {

  @Autowired private DevicePairingRepository pairingRepository;

  @Autowired private DevicePairingService pairingService;

  @Value("${hr.pairing-exit.enabled:true}")
  private boolean enabled = true;

  private Clock clock = Clock.systemUTC();

  /** Tests pin the clock. */
  void setClock(Clock clock) {
    this.clock = clock;
  }

  @Scheduled(fixedDelayString = "${hr.pairing-exit.interval-ms:60000}")
  public void run() {
    if (!enabled) {
      return;
    }
    try {
      int done = unpairDue(Instant.now(clock));
      if (done > 0) {
        log.info("[hr-pairing] exit job unpaired {} phone(s)", done);
      }
    } catch (RuntimeException e) {
      // the read itself failed; the schedules are untouched and the next tick tries again
      log.warn("[hr-pairing] exit job failed: {}", e.getMessage(), e);
    }
  }

  /**
   * Unpairs every active pairing with {@code scheduledUnpairAt <= now}; one failure does not stop
   * the others.
   *
   * @return how many pairings this call unpaired
   */
  public int unpairDue(Instant now) {
    List<DevicePairing> due = pairingRepository
        .findByStatusAndScheduledUnpairAtLessThanEqual(PairingStatus.ACTIVE, now);
    int done = 0;
    for (DevicePairing p : due) {
      try {
        if (pairingService.unpair(p.getId(), UnpairReason.EMPLOYEE_EXITED,
            DevicePairingService.SYSTEM).isPresent()) {
          done++;
        }
      } catch (RuntimeException e) {
        log.warn("[hr-pairing] exit unpair of pairing {} (shop {}) failed, will retry: {}",
            p.getId(), p.getShopId(), e.getMessage());
      }
    }
    return done;
  }
}
