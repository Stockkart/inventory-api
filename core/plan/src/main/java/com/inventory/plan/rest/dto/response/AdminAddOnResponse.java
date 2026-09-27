package com.inventory.plan.rest.dto.response;

import java.time.Instant;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

@Data
@SuperBuilder
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class AdminAddOnResponse extends AddOnResponse {

  private String id;
  private boolean active;
  private Instant createdAt;
  private Instant updatedAt;
}
