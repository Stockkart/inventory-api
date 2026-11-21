package com.inventory.plan.utils.constants;

import java.time.ZoneId;

public final class CampaignConstants {

  private CampaignConstants() {}

  /** "Days left" is counted in calendar days where the customers are. */
  public static final ZoneId CAMPAIGN_ZONE = ZoneId.of("Asia/Kolkata");

  public static final int DEFAULT_IMMINENT_THRESHOLD_DAYS = 3;
}
