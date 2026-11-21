package com.inventory.plan.rest.dto.response;

import com.inventory.plan.domain.model.CampaignState;
import com.inventory.plan.domain.model.CampaignTheme;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * The one campaign to show right now. {@code state} is authoritative; the client only ticks a
 * countdown between refetches, corrected by {@code serverNow}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CampaignResponse {

  private String code;
  private CampaignState state;
  /** Teaser headline while UPCOMING/STARTING_SOON, live headline otherwise. */
  private String headline;
  private String subtext;
  private String ctaLabel;
  private String ctaPath;
  private CampaignTheme theme;
  private Instant startsAt;
  private Instant endsAt;
  private boolean dismissible;
  private Instant serverNow;
  /** When {@code state} next changes; refetch then instead of recomputing state on the client. */
  private Instant nextTransitionAt;
}
