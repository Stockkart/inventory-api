package com.inventory.plan.rest.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** One row of campaign_themes, for the theme picker in the admin app. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CampaignThemeResponse {

  private String code;
  private String label;
  private Integer sortOrder;
  /** Inactive themes are listed so existing campaigns still show a label, but cannot be picked. */
  private boolean active;
}
