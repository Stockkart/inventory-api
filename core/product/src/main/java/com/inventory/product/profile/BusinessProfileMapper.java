package com.inventory.product.profile;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.inventory.pluginengine.profile.BusinessProfile;
import com.inventory.pluginengine.profile.EntityProfile;
import com.inventory.pluginengine.profile.FieldDefinition;
import com.inventory.product.domain.model.BusinessType;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class BusinessProfileMapper {

  private final ObjectMapper objectMapper;

  public BusinessProfileMapper(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  public BusinessProfile toRuntimeProfile(BusinessType entity) {
    if (entity == null) {
      return null;
    }
    BusinessProfile profile = new BusinessProfile();
    profile.setId(entity.getId());
    profile.setCode(entity.getCode());
    profile.setName(entity.getName());
    profile.setVersion(entity.getVersion() != null ? entity.getVersion() : 1);
    profile.setModules(entity.getModules() != null ? entity.getModules() : Collections.emptyMap());
    profile.setPricing(entity.getPricing() != null ? entity.getPricing() : Collections.emptyMap());
    profile.setStrategies(entity.getStrategies() != null ? entity.getStrategies() : Collections.emptyMap());
    profile.setCompliance(entity.getCompliance() != null ? entity.getCompliance() : Collections.emptyMap());
    profile.setUi(entity.getUi() != null ? entity.getUi() : Collections.emptyMap());
    profile.setEntities(parseEntities(entity.getEntities()));
    return profile;
  }

  private Map<String, EntityProfile> parseEntities(Map<String, Object> raw) {
    if (raw == null || raw.isEmpty()) {
      return Collections.emptyMap();
    }
    Map<String, EntityProfile> result = new LinkedHashMap<>();
    for (Map.Entry<String, Object> entry : raw.entrySet()) {
      EntityProfile entityProfile = objectMapper.convertValue(entry.getValue(), EntityProfile.class);
      if (entityProfile != null && entityProfile.getFields() != null) {
        for (FieldDefinition field : entityProfile.getFields()) {
          if (field.getVisible() == null) {
            field.setVisible(true);
          }
        }
      }
      result.put(entry.getKey(), entityProfile);
    }
    return result;
  }

  public Map<String, Object> entitiesToMap(Map<String, EntityProfile> entities) {
    if (entities == null) {
      return Collections.emptyMap();
    }
    return objectMapper.convertValue(entities, new TypeReference<Map<String, Object>>() {});
  }
}
