package com.inventory.pluginengine.schema;

import java.util.List;
import lombok.Data;

@Data
public class VerticalSchemaField {

  private String key;
  private String label;
  private String type;
  private Boolean required;
  private String storage;
  private String tier;
  private Boolean indexed;
  private Boolean searchable;
  private Boolean sortable;
  private String group;
  private List<String> showIn;
  private List<String> values;
}
