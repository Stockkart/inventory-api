package com.inventory.pluginengine.schema;

import java.util.List;
import lombok.Data;

@Data
public class VerticalEntitySchema {

  private List<VerticalSchemaField> fields;
}
