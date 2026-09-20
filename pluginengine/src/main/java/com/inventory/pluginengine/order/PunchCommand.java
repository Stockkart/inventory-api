package com.inventory.pluginengine.order;

import java.util.List;
import lombok.Builder;
import lombok.Data;

/** A request to punch one round to the kitchen. */
@Data
@Builder
public class PunchCommand {

  private String shopId;
  private String userId;
  private String orderId;

  /** Required. Without it a dropped response would send the same food to the kitchen twice. */
  private String idempotencyKey;

  private List<PunchLine> lines;
}
