package com.inventory.pluginengine.schema;

import java.util.List;
import java.util.Map;
import lombok.Data;

@Data
public class VerticalSchemaField {

  private String key;
  /** REST/entity property name when it differs from {@link #key} (rare; prefer same name as schema key). */
  private String apiKey;
  private String label;
  private String type;
  private Boolean required;
  private String storage;
  /** UI density for optional fields: {@code regular} (full form) or {@code basic} (quick entry). */
  private String tier;
  private Boolean indexed;
  private Boolean searchable;
  private Boolean sortable;
  private String group;
  private List<String> showIn;
  private List<String> values;
  /** Declarative rules, e.g. {@code notPastOnCreate}, {@code min}, {@code max}. */
  private Map<String, Object> validation;
}
