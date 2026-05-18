package com.inventory.pluginengine.profile;

import lombok.Data;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Runtime view of a business profile (loaded from business_types collection).
 */
@Data
public class BusinessProfile {

  public static final String DEFAULT_PROFILE_ID = "pharmacy";

  private String id;
  private String code;
  private String name;
  private int version;
  private Map<String, Boolean> modules = Collections.emptyMap();
  private Map<String, EntityProfile> entities = Collections.emptyMap();
  private Map<String, Object> pricing = Collections.emptyMap();
  private Map<String, String> strategies = Collections.emptyMap();
  private Map<String, Object> compliance = Collections.emptyMap();
  private Map<String, Object> ui = Collections.emptyMap();

  public boolean isModuleEnabled(String moduleKey) {
    if (moduleKey == null) {
      return false;
    }
    return Boolean.TRUE.equals(modules.get(moduleKey));
  }

  public String strategy(String key) {
    if (strategies == null || key == null) {
      return null;
    }
    return strategies.get(key);
  }

  public List<FieldDefinition> fieldsForEntity(String entityKey) {
    if (entities == null || entityKey == null) {
      return List.of();
    }
    EntityProfile entity = entities.get(entityKey);
    if (entity == null || entity.getFields() == null) {
      return List.of();
    }
    return entity.getFields();
  }

  public FieldDefinition field(String entityKey, String fieldKey) {
    return fieldsForEntity(entityKey).stream()
        .filter(f -> fieldKey.equals(f.getKey()))
        .findFirst()
        .orElse(null);
  }

  public boolean isFieldVisible(String entityKey, String fieldKey) {
    FieldDefinition field = field(entityKey, fieldKey);
    if (field == null) {
      return true;
    }
    return field.getVisible() == null || field.getVisible();
  }

  public boolean isFieldRequired(String entityKey, String fieldKey) {
    FieldDefinition field = field(entityKey, fieldKey);
    return field != null && Boolean.TRUE.equals(field.getRequired());
  }
}
