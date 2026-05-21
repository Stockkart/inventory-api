package com.inventory.pluginengine.profile;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EntityProfile {

  private List<FieldDefinition> fields;

  public List<FieldDefinition> getFields() {
    return fields != null ? fields : Collections.emptyList();
  }

  public Optional<FieldDefinition> findField(String key) {
    if (key == null) {
      return Optional.empty();
    }
    return getFields().stream().filter(f -> key.equals(f.getKey())).findFirst();
  }
}
