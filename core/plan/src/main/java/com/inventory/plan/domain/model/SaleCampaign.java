package com.inventory.plan.domain.model;

import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * A seasonal sale banner. Campaigns announce the permanent pricing only; they never change what a
 * plan costs.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "sale_campaigns")
public class SaleCampaign {

  @Id
  private String id;
  /** Stable key, e.g. MONSOON_2026. Dismissals are stored against it. */
  private String code;
  private String headline;
  private String subtext;
  /** Teaser headline before the sale starts; falls back to headline. */
  private String upcomingHeadline;
  private String ctaLabel;
  /** App-relative path such as /plans. */
  private String ctaPath;
  private CampaignTheme theme;
  private Instant startsAt;
  private Instant endsAt;
  /** When the teaser becomes visible. Null = no teaser; the banner appears at startsAt. */
  private Instant announceFrom;
  /** Calendar days (Asia/Kolkata) before start or end that count as "soon". Null = 3. */
  private Integer imminentThresholdDays;
  private boolean dismissible;
  /** Higher wins when two campaigns compete in the same state. */
  private int priority;
  /** Manual kill switch. Null is treated as active. */
  private Boolean active;
  private Instant createdAt;
  private Instant updatedAt;
}
