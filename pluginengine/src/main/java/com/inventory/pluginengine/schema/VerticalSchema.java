package com.inventory.pluginengine.schema;

import java.util.Map;
import lombok.Data;

@Data
public class VerticalSchema {

  private String verticalId;
  private String version;
  private Map<String, VerticalEntitySchema> entities;
  /** Backend workflow policy (e.g. billingMode, tokenEnabled). Not used for UI routing. */
  private Map<String, Object> workflows;
}
