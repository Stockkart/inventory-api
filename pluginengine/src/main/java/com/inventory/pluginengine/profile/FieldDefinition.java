package com.inventory.pluginengine.profile;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FieldDefinition {

  private String key;
  private Boolean required;
  /** When false, field is not shown on forms. Defaults to true when null. */
  private Boolean visible;
  private String storage;
  private String label;
}
