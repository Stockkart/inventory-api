package com.inventory.pluginengine.profile;

import lombok.Data;

import java.util.Map;

@Data
public class FieldDefinition {
  private String key;
  private String type;
  private Boolean required;
  private Boolean visible;
  private String label;
  /** column | pricing | attributes */
  private String storage;
  private Map<String, String> requiredWhen;
}
