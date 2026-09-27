package com.inventory.plan.rest.dto.request;

import com.inventory.plan.domain.model.CampaignTheme;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Create or full edit. {@code code} is fixed after create; edits ignore it. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CampaignRequest {

  private String code;
  private String headline;
  private String subtext;
  private String upcomingHeadline;
  private String ctaLabel;
  private String ctaPath;
  private CampaignTheme theme;
  private Instant startsAt;
  private Instant endsAt;
  private Instant announceFrom;
  private Integer imminentThresholdDays;
  private boolean dismissible;
  private int priority;
}
