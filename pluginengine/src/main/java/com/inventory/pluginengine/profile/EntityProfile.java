package com.inventory.pluginengine.profile;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class EntityProfile {
  private List<FieldDefinition> fields = new ArrayList<>();
}
