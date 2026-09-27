package com.inventory.plan.service;

import com.inventory.plan.domain.model.CampaignState;
import com.inventory.plan.domain.model.SaleCampaign;
import com.inventory.plan.domain.repository.SaleCampaignRepository;
import com.inventory.plan.rest.dto.response.CampaignResponse;
import com.inventory.plan.utils.constants.CampaignConstants;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Picks the one sale banner to show and derives its state from the dates. State lives here, not in
 * the browser, so a wrong device clock cannot show a sale as live.
 */
@Service
@Slf4j
public class CampaignService {

  @Autowired
  private SaleCampaignRepository saleCampaignRepository;

  @Autowired
  private MongoTemplate mongoTemplate;

  Clock clock = Clock.systemUTC();

  /** Auto index creation is off in this application. */
  @EventListener(ApplicationReadyEvent.class)
  public void ensureIndexes() {
    mongoTemplate.indexOps(SaleCampaign.class)
        .ensureIndex(new Index().on("code", Sort.Direction.ASC).unique().named("code_unique"));
    mongoTemplate.indexOps(SaleCampaign.class)
        .ensureIndex(new Index().on("endsAt", Sort.Direction.ASC).named("endsAt"));
  }

  /** A live campaign outranks an upcoming one; then higher priority, then the earlier start. */
  public Optional<CampaignResponse> findActive() {
    Instant now = clock.instant();
    return saleCampaignRepository.findByEndsAtAfter(now).stream()
        .filter(CampaignService::isWellFormed)
        .map(c -> new Candidate(c, stateOf(c, now)))
        .filter(candidate -> candidate.state() != null)
        .min(Comparator
            .comparing((Candidate candidate) -> !candidate.state().isLive())
            .thenComparing(candidate -> -candidate.campaign().getPriority())
            .thenComparing(candidate -> candidate.campaign().getStartsAt())
            .thenComparing(candidate -> candidate.campaign().getCode(),
                Comparator.nullsLast(Comparator.naturalOrder())))
        .map(candidate -> toResponse(candidate, now));
  }

  /** Null when the campaign should not be shown at {@code now}. */
  static CampaignState stateOf(SaleCampaign campaign, Instant now) {
    if (Boolean.FALSE.equals(campaign.getActive()) || !now.isBefore(campaign.getEndsAt())) {
      return null;
    }
    int threshold = threshold(campaign);
    LocalDate today = LocalDate.ofInstant(now, CampaignConstants.CAMPAIGN_ZONE);
    if (!now.isBefore(campaign.getStartsAt())) {
      long daysLeft = ChronoUnit.DAYS.between(today, lastDay(campaign));
      return daysLeft < threshold ? CampaignState.ENDING_SOON : CampaignState.LIVE;
    }
    if (campaign.getAnnounceFrom() == null || now.isBefore(campaign.getAnnounceFrom())) {
      return null;
    }
    long daysToStart = ChronoUnit.DAYS.between(today, startDay(campaign));
    return daysToStart <= threshold ? CampaignState.STARTING_SOON : CampaignState.UPCOMING;
  }

  /** When {@code state} next changes, so the client refetches then instead of guessing. */
  static Instant nextTransitionAt(SaleCampaign campaign, CampaignState state) {
    int threshold = threshold(campaign);
    return switch (state) {
      case UPCOMING -> earliest(
          startOfDay(startDay(campaign).minusDays(threshold)), campaign.getStartsAt());
      case STARTING_SOON -> campaign.getStartsAt();
      case LIVE -> threshold == 0
          ? campaign.getEndsAt()
          : earliest(startOfDay(lastDay(campaign).minusDays(threshold - 1L)), campaign.getEndsAt());
      case ENDING_SOON -> campaign.getEndsAt();
    };
  }

  private static CampaignResponse toResponse(Candidate candidate, Instant now) {
    SaleCampaign campaign = candidate.campaign();
    CampaignState state = candidate.state();
    String headline = !state.isLive() && StringUtils.hasText(campaign.getUpcomingHeadline())
        ? campaign.getUpcomingHeadline()
        : campaign.getHeadline();
    return CampaignResponse.builder()
        .code(campaign.getCode())
        .state(state)
        .headline(headline)
        .subtext(campaign.getSubtext())
        .ctaLabel(campaign.getCtaLabel())
        .ctaPath(campaign.getCtaPath())
        .theme(campaign.getTheme())
        .startsAt(campaign.getStartsAt())
        .endsAt(campaign.getEndsAt())
        .dismissible(campaign.isDismissible())
        .serverNow(now)
        .nextTransitionAt(nextTransitionAt(campaign, state))
        .build();
  }

  private static boolean isWellFormed(SaleCampaign campaign) {
    boolean ok = campaign.getStartsAt() != null && campaign.getEndsAt() != null
        && campaign.getStartsAt().isBefore(campaign.getEndsAt());
    if (!ok) {
      log.warn("Skipping campaign {} with invalid dates", campaign.getCode());
    }
    return ok;
  }

  private static int threshold(SaleCampaign campaign) {
    Integer days = campaign.getImminentThresholdDays();
    return days == null ? CampaignConstants.DEFAULT_IMMINENT_THRESHOLD_DAYS : Math.max(0, days);
  }

  private static LocalDate startDay(SaleCampaign campaign) {
    return LocalDate.ofInstant(campaign.getStartsAt(), CampaignConstants.CAMPAIGN_ZONE);
  }

  /** A sale ending at midnight has its last day the day before. */
  private static LocalDate lastDay(SaleCampaign campaign) {
    return LocalDate.ofInstant(campaign.getEndsAt().minusMillis(1), CampaignConstants.CAMPAIGN_ZONE);
  }

  private static Instant startOfDay(LocalDate day) {
    return day.atStartOfDay(CampaignConstants.CAMPAIGN_ZONE).toInstant();
  }

  private static Instant earliest(Instant a, Instant b) {
    return a.isBefore(b) ? a : b;
  }

  private record Candidate(SaleCampaign campaign, CampaignState state) {}
}
