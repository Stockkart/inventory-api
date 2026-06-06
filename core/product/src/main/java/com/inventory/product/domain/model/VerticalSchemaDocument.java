package com.inventory.product.domain.model;

import com.inventory.pluginengine.schema.VerticalSchema;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "vertical_schemas")
@CompoundIndex(name = "vertical_version", def = "{'verticalId': 1, 'version': 1}", unique = true)
public class VerticalSchemaDocument {

  @Id
  private String id;

  private String verticalId;
  private String version;
  private String status;
  private VerticalSchema schema;
  private Instant publishedAt;
  private String createdBy;
}
