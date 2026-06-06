package com.inventory.pluginengine.schema;

import java.util.Map;
import lombok.Data;

@Data
public class VerticalSchema {

  private String verticalId;
  private String version;
  private Map<String, VerticalEntitySchema> entities;
  /**
   * Optional tax layout hints for invoice UI. Example: intrastate → CGST+SGST, interstate → IGST.
   */
  private Map<String, Object> taxRules;
}
