package com.inventory.plan.domain.model;

import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * A named banner look, managed in the database. Campaigns store its {@code code}; ui-kit owns what
 * each code looks like and falls back to the default look for a code it does not know.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "campaign_themes")
public class CampaignTheme {

  @Id
  private String id;
  /** Stable key such as DIWALI. Unique. */
  private String code;
  /** Name shown in the admin app, such as "Diwali". */
  private String label;
  /** Lower comes first in the admin list. Null sorts last. */
  private Integer sortOrder;
  /** Inactive themes cannot be picked for new or edited campaigns. Null is treated as active. */
  private Boolean active;
  private Instant createdAt;
  private Instant updatedAt;
}
