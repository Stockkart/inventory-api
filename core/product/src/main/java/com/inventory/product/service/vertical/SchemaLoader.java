package com.inventory.product.service.vertical;

import com.inventory.common.exception.ResourceNotFoundException;
import com.inventory.pluginengine.schema.VerticalSchema;
import com.inventory.pluginengine.schema.VerticalSchemaStatus;
import com.inventory.product.domain.model.VerticalSchemaDocument;
import com.inventory.product.domain.repository.VerticalSchemaRepository;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
@Slf4j
public class SchemaLoader {

  private static final String ACTIVE = VerticalSchemaStatus.ACTIVE.name();

  private final VerticalSchemaRepository schemaRepository;
  private final Map<String, VerticalSchema> cache = new ConcurrentHashMap<>();

  public SchemaLoader(VerticalSchemaRepository schemaRepository) {
    this.schemaRepository = schemaRepository;
  }

  public VerticalSchema load(String verticalId, String version) {
    String resolvedVersion = resolveVersionFromDb(verticalId, version);
    String cacheKey = verticalId.trim().toLowerCase() + ":" + resolvedVersion;
    return cache.computeIfAbsent(cacheKey, k -> loadFromDb(verticalId, resolvedVersion));
  }

  public void evictCache() {
    cache.clear();
  }

  private String resolveVersionFromDb(String verticalId, String version) {
    if (StringUtils.hasText(version)) {
      return version.trim();
    }
    return schemaRepository
        .findByVerticalIdAndStatus(verticalId.trim().toLowerCase(), ACTIVE)
        .stream()
        .findFirst()
        .map(VerticalSchemaDocument::getVersion)
        .orElseThrow(
            () ->
                new ResourceNotFoundException(
                    "VerticalSchema",
                    "verticalId",
                    verticalId + " (no ACTIVE schema or missing pluginVersion on shop)"));
  }

  private VerticalSchema loadFromDb(String verticalId, String version) {
    VerticalSchemaDocument doc =
        schemaRepository
            .findByVerticalIdAndVersion(verticalId.trim().toLowerCase(), version)
            .orElseThrow(
                () ->
                    new ResourceNotFoundException(
                        "VerticalSchema",
                        "verticalId+version",
                        verticalId + "+" + version));
    VerticalSchema schema = doc.getSchema();
    if (schema == null) {
      throw new ResourceNotFoundException(
          "VerticalSchema", "verticalId+version", verticalId + "+" + version);
    }
    log.debug("Loaded schema {} v{} from vertical_schemas", verticalId, version);
    return schema;
  }
}
