package com.inventory.product.rest.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.inventory.pluginengine.schema.VerticalEntitySchema;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ShopSchemaResponse {

  private String shopId;
  private String verticalId;
  private String pluginVersion;
  private String mode;
  private Map<String, VerticalEntitySchema> entities;
}
