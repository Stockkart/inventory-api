package com.inventory.product.service.vertical;

import com.inventory.common.exception.ResourceNotFoundException;
import com.inventory.pluginengine.schema.VerticalSchema;
import com.inventory.pluginengine.schema.VerticalSchemaStatus;
import com.inventory.product.domain.model.VerticalSchemaDocument;
import com.inventory.product.domain.repository.VerticalSchemaRepository;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * In-memory cache for vertical schemas. MongoDB is read at startup ({@link #warmCache()}) and on
 * cache miss; routine validation uses cached objects only.
 */
@Service
@Slf4j
public class SchemaLoader {

  private static final String ACTIVE = VerticalSchemaStatus.ACTIVE.name();

  private final VerticalSchemaRepository schemaRepository;
  private final Map<String, VerticalSchema> schemaCache = new ConcurrentHashMap<>();
  private final Map<String, String> activeVersionCache = new ConcurrentHashMap<>();

  public SchemaLoader(VerticalSchemaRepository schemaRepository) {
    this.schemaRepository = schemaRepository;
  }

  public VerticalSchema load(String verticalId, String version) {
    String vid = verticalId.trim().toLowerCase();
    String resolvedVersion = resolveVersion(vid, version);
    String cacheKey = cacheKey(vid, resolvedVersion);
    return schemaCache.computeIfAbsent(cacheKey, k -> loadFromDb(vid, resolvedVersion));
  }

  /** Loads all ACTIVE schemas from MongoDB into memory. Called once on application startup. */
  public void warmCache() {
    List<VerticalSchemaDocument> active = schemaRepository.findByStatus(ACTIVE);
    int loaded = 0;
    for (VerticalSchemaDocument doc : active) {
      if (doc.getVerticalId() == null || doc.getVersion() == null || doc.getSchema() == null) {
        continue;
      }
      String vid = doc.getVerticalId().trim().toLowerCase();
      String version = doc.getVersion().trim();
      activeVersionCache.putIfAbsent(vid, version);
      schemaCache.put(cacheKey(vid, version), doc.getSchema());
      loaded++;
    }
    log.info("Warmed vertical schema cache: {} ACTIVE schema(s)", loaded);
  }

  public void evictCache() {
    schemaCache.clear();
    activeVersionCache.clear();
    log.info("Evicted vertical schema cache");
  }

  private String resolveVersion(String verticalId, String version) {
    if (StringUtils.hasText(version)) {
      return version.trim();
    }
    return activeVersionCache.computeIfAbsent(verticalId, this::loadActiveVersionFromDb);
  }

  private String loadActiveVersionFromDb(String verticalId) {
    return schemaRepository
        .findByVerticalIdAndStatus(verticalId, ACTIVE)
        .stream()
        .findFirst()
        .map(VerticalSchemaDocument::getVersion)
        .map(String::trim)
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
            .findByVerticalIdAndVersion(verticalId, version)
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
    log.debug("Loaded schema {} v{} from vertical_schemas (cache miss)", verticalId, version);
    return schema;
  }

  private static String cacheKey(String verticalId, String version) {
    return verticalId + ":" + version;
  }
}
