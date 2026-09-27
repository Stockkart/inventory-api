package com.inventory.plan.rest.dto.response;

import com.inventory.plan.domain.model.CampaignState;
import com.inventory.plan.domain.model.CampaignTheme;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AdminCampaignResponse {

  private String id;
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
  private boolean active;
  /** What shoppers would see right now; null when hidden (not announced, ended, or switched off). */
  private CampaignState state;
  private Instant createdAt;
  private Instant updatedAt;
}
