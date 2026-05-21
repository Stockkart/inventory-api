package com.inventory.product.domain.model.profile;

import lombok.Data;

@Data
public class FieldDefinitionDoc {

  private String key;
  private Boolean required;
  private Boolean visible;
  private String storage;
  private String label;
}
