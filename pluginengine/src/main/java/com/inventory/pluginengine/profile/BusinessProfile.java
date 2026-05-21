package com.inventory.pluginengine.profile;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Collections;
import java.util.Map;
import java.util.Optional;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BusinessProfile {

  public static final String DEFAULT_PROFILE_ID = "pharmacy";

  private String id;
  private String code;
  private String name;
  private boolean enabled;
  private Integer version;
  private Map<String, Boolean> modules;
  private Map<String, EntityProfile> entities;
  private Map<String, String> strategies;
  private Map<String, Object> pricing;
  private ProfileUi ui;

  public Map<String, Boolean> getModules() {
    return modules != null ? modules : Collections.emptyMap();
  }

  public Map<String, EntityProfile> getEntities() {
    return entities != null ? entities : Collections.emptyMap();
  }

  public Map<String, String> getStrategies() {
    return strategies != null ? strategies : Collections.emptyMap();
  }

  public Map<String, Object> getPricing() {
    return pricing != null ? pricing : Collections.emptyMap();
  }

  public ProfileUi getUi() {
    return ui != null ? ui : ProfileUi.builder().build();
  }

  public boolean isModuleEnabled(String moduleKey) {
    return Boolean.TRUE.equals(getModules().get(moduleKey));
  }

  public Optional<FieldDefinition> getEntityField(String entity, String fieldKey) {
    EntityProfile entityProfile = getEntities().get(entity);
    if (entityProfile == null) {
      return Optional.empty();
    }
    return entityProfile.findField(fieldKey);
  }

  public boolean isFieldVisible(String entity, String fieldKey) {
    return getEntityField(entity, fieldKey)
        .map(f -> f.getVisible() == null || Boolean.TRUE.equals(f.getVisible()))
        .orElse(true);
  }

  public boolean isFieldRequired(String entity, String fieldKey) {
    return getEntityField(entity, fieldKey)
        .map(f -> Boolean.TRUE.equals(f.getRequired()))
        .orElse(false);
  }
}
