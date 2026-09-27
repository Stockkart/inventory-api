package com.inventory.plan.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.inventory.plan.domain.model.CampaignState;
import com.inventory.plan.domain.model.CampaignTheme;
import com.inventory.plan.domain.model.SaleCampaign;
import com.inventory.plan.domain.repository.SaleCampaignRepository;
import com.inventory.plan.rest.dto.response.CampaignResponse;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CampaignServiceTest {

  private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

  @Mock
  private SaleCampaignRepository saleCampaignRepository;

  @InjectMocks
  private CampaignService campaignService;

  /** Sale runs 10 Oct 00:00 IST to 20 Oct 00:00 IST (last day 19 Oct); teaser from 1 Oct. */
  private static SaleCampaign.SaleCampaignBuilder monsoon() {
    return SaleCampaign.builder()
        .code("MONSOON_2026")
        .headline("Monsoon sale is live")
        .upcomingHeadline("Monsoon sale is coming")
        .theme(CampaignTheme.MONSOON)
        .startsAt(ist(2026, 10, 10, 0, 0))
        .endsAt(ist(2026, 10, 20, 0, 0))
        .announceFrom(ist(2026, 10, 1, 0, 0))
        .dismissible(true);
  }

  private static Instant ist(int y, int mo, int d, int h, int mi) {
    return LocalDateTime.of(y, mo, d, h, mi).atZone(IST).toInstant();
  }

  private void setNow(Instant now) {
    campaignService.clock = Clock.fixed(now, ZoneOffset.UTC);
  }

  @Test
  void hiddenBeforeAnnounceFrom() {
    assertThat(CampaignService.stateOf(monsoon().build(), ist(2026, 9, 30, 23, 59))).isNull();
  }

  @Test
  void upcomingUntilThresholdDaysBeforeStart() {
    SaleCampaign c = monsoon().build();
    assertThat(CampaignService.stateOf(c, ist(2026, 10, 1, 0, 0))).isEqualTo(CampaignState.UPCOMING);
    assertThat(CampaignService.stateOf(c, ist(2026, 10, 6, 23, 59))).isEqualTo(CampaignState.UPCOMING);
    assertThat(CampaignService.nextTransitionAt(c, CampaignState.UPCOMING)).isEqualTo(ist(2026, 10, 7, 0, 0));
  }

  @Test
  void startingSoonCountsIstCalendarDaysNotHours() {
    SaleCampaign c = monsoon().build();
    // 7 Oct 00:00 IST is 6 Oct 18:30 UTC; a UTC calendar would still call this UPCOMING.
    assertThat(CampaignService.stateOf(c, ist(2026, 10, 7, 0, 0))).isEqualTo(CampaignState.STARTING_SOON);
    assertThat(CampaignService.stateOf(c, ist(2026, 10, 9, 23, 59))).isEqualTo(CampaignState.STARTING_SOON);
    assertThat(CampaignService.nextTransitionAt(c, CampaignState.STARTING_SOON)).isEqualTo(c.getStartsAt());
  }

  @Test
  void liveThenEndingSoonForTheLastThresholdDays() {
    SaleCampaign c = monsoon().build();
    assertThat(CampaignService.stateOf(c, ist(2026, 10, 10, 0, 0))).isEqualTo(CampaignState.LIVE);
    assertThat(CampaignService.stateOf(c, ist(2026, 10, 16, 23, 59))).isEqualTo(CampaignState.LIVE);
    assertThat(CampaignService.nextTransitionAt(c, CampaignState.LIVE)).isEqualTo(ist(2026, 10, 17, 0, 0));
    assertThat(CampaignService.stateOf(c, ist(2026, 10, 17, 0, 0))).isEqualTo(CampaignState.ENDING_SOON);
    assertThat(CampaignService.stateOf(c, ist(2026, 10, 19, 23, 59))).isEqualTo(CampaignState.ENDING_SOON);
    assertThat(CampaignService.nextTransitionAt(c, CampaignState.ENDING_SOON)).isEqualTo(c.getEndsAt());
  }

  @Test
  void hiddenAtEndsAtAndWhenSwitchedOff() {
    assertThat(CampaignService.stateOf(monsoon().build(), ist(2026, 10, 20, 0, 0))).isNull();
    assertThat(CampaignService.stateOf(monsoon().active(false).build(), ist(2026, 10, 12, 0, 0))).isNull();
  }

  @Test
  void noTeaserWithoutAnnounceFrom() {
    SaleCampaign c = monsoon().announceFrom(null).build();
    assertThat(CampaignService.stateOf(c, ist(2026, 10, 9, 12, 0))).isNull();
    assertThat(CampaignService.stateOf(c, ist(2026, 10, 10, 0, 0))).isEqualTo(CampaignState.LIVE);
  }

  @Test
  void zeroThresholdNeverReportsSoon() {
    SaleCampaign c = monsoon().imminentThresholdDays(0).build();
    assertThat(CampaignService.stateOf(c, ist(2026, 10, 9, 23, 59))).isEqualTo(CampaignState.UPCOMING);
    assertThat(CampaignService.stateOf(c, ist(2026, 10, 19, 23, 59))).isEqualTo(CampaignState.LIVE);
    assertThat(CampaignService.nextTransitionAt(c, CampaignState.LIVE)).isEqualTo(c.getEndsAt());
  }

  @Test
  void liveOutranksUpcomingEvenWithLowerPriority() {
    Instant now = ist(2026, 10, 12, 12, 0);
    setNow(now);
    SaleCampaign live = monsoon().priority(0).build();
    SaleCampaign upcoming = monsoon().code("DIWALI_2026").priority(10)
        .startsAt(ist(2026, 11, 1, 0, 0)).endsAt(ist(2026, 11, 10, 0, 0))
        .announceFrom(ist(2026, 10, 1, 0, 0)).build();
    when(saleCampaignRepository.findByEndsAtAfter(now)).thenReturn(List.of(upcoming, live));

    Optional<CampaignResponse> result = campaignService.findActive();

    assertThat(result).get().extracting(CampaignResponse::getCode).isEqualTo("MONSOON_2026");
    assertThat(result.get().getState()).isEqualTo(CampaignState.LIVE);
    assertThat(result.get().getHeadline()).isEqualTo("Monsoon sale is live");
    assertThat(result.get().getServerNow()).isEqualTo(now);
  }

  @Test
  void higherPriorityWinsWithinTheSameTier() {
    Instant now = ist(2026, 10, 12, 12, 0);
    setNow(now);
    SaleCampaign low = monsoon().priority(1).build();
    SaleCampaign high = monsoon().code("FLASH").priority(5).build();
    when(saleCampaignRepository.findByEndsAtAfter(any())).thenReturn(List.of(low, high));

    assertThat(campaignService.findActive()).get().extracting(CampaignResponse::getCode).isEqualTo("FLASH");
  }

  @Test
  void teaserUsesUpcomingHeadlineAndSkipsBadDates() {
    Instant now = ist(2026, 10, 3, 9, 0);
    setNow(now);
    SaleCampaign broken = monsoon().code("BROKEN").startsAt(ist(2026, 10, 20, 0, 0)).build();
    when(saleCampaignRepository.findByEndsAtAfter(now)).thenReturn(List.of(broken, monsoon().build()));

    CampaignResponse response = campaignService.findActive().orElseThrow();

    assertThat(response.getCode()).isEqualTo("MONSOON_2026");
    assertThat(response.getState()).isEqualTo(CampaignState.UPCOMING);
    assertThat(response.getHeadline()).isEqualTo("Monsoon sale is coming");
    assertThat(response.getNextTransitionAt()).isEqualTo(ist(2026, 10, 7, 0, 0));
  }

  @Test
  void emptyWhenNothingToShow() {
    setNow(ist(2026, 9, 1, 0, 0));
    when(saleCampaignRepository.findByEndsAtAfter(any())).thenReturn(List.of(monsoon().build()));

    assertThat(campaignService.findActive()).isEmpty();
  }
}
