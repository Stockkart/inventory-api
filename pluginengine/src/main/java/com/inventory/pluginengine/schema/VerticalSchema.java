package com.inventory.pluginengine.schema;

import java.util.Map;
import lombok.Data;

@Data
public class VerticalSchema {

  private String verticalId;
  private String version;
  private Map<String, VerticalEntitySchema> entities;
}
