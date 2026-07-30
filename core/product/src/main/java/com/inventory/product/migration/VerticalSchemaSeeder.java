package com.inventory.product.migration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.inventory.pluginengine.schema.VerticalSchemaStatus;
import com.inventory.pluginengine.schema.VerticalSchema;
import com.inventory.product.domain.model.VerticalSchemaDocument;
import com.inventory.product.domain.repository.VerticalSchemaRepository;
import com.inventory.product.service.vertical.SchemaLoader;
import java.io.IOException;
import java.time.Instant;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

/**
 * Seeds {@code vertical_schemas} from {@code classpath:seeds/*.json}. Runtime validation reads
 * MongoDB only.
 *
 * <p>Inserts when no row exists for that vertical + version, and <em>reconciles</em> the stored body
 * when it has drifted from the seed file.
 *
 * <p>Reconciling matters because seed files are edited in place without bumping the version. This
 * previously skipped any existing row, so a field added to a seed reached only environments whose
 * row did not exist yet — a fresh developer database picked it up while a long-lived one stayed
 * frozen on the old field list forever, no matter how many times the service was redeployed. Adding
 * {@code itemType} to the medical vertical did exactly that.
 */
@Component
@Slf4j
public class VerticalSchemaSeeder {

  private static final String SEED_PATTERN = "classpath:seeds/*.json";

  @Autowired private VerticalSchemaRepository schemaRepository;
  @Autowired private SchemaLoader schemaLoader;
  @Autowired private ObjectMapper objectMapper;

  @EventListener(ApplicationReadyEvent.class)
  @Order(10)
  public void seedOnStartup() {
    boolean anyChanged = false;
    try {
      Resource[] resources = new PathMatchingResourcePatternResolver().getResources(SEED_PATTERN);
      for (Resource resource : resources) {
        if (seedResource(resource)) {
          anyChanged = true;
        }
      }
    } catch (IOException e) {
      log.error("Failed to scan {}: {}", SEED_PATTERN, e.getMessage(), e);
    }
    if (anyChanged) {
      schemaLoader.evictCache();
    }
    schemaLoader.warmCache();
  }

  private boolean seedResource(Resource resource) {
    try {
      VerticalSchema schema = objectMapper.readValue(resource.getInputStream(), VerticalSchema.class);
      if (schema.getVerticalId() == null || schema.getVersion() == null) {
        log.warn("Skip seed {} — missing verticalId or version", resource.getFilename());
        return false;
      }
      String verticalId = schema.getVerticalId().trim().toLowerCase();
      String version = schema.getVersion().trim();

      Optional<VerticalSchemaDocument> existing =
          schemaRepository.findByVerticalIdAndVersion(verticalId, version);
      if (existing.isPresent()) {
        return reconcile(existing.get(), schema, resource);
      }

      VerticalSchemaDocument doc = new VerticalSchemaDocument();
      doc.setId(verticalId + "_" + version);
      doc.setVerticalId(verticalId);
      doc.setVersion(version);
      doc.setStatus(VerticalSchemaStatus.ACTIVE.name());
      doc.setSchema(schema);
      doc.setPublishedAt(Instant.now());
      doc.setCreatedBy("seed:" + resource.getFilename());
      schemaRepository.save(doc);
      log.info("Seeded vertical_schemas {} from {}", doc.getId(), resource.getFilename());
      return true;
    } catch (Exception e) {
      log.error("Failed to seed from {}: {}", resource.getFilename(), e.getMessage(), e);
      return false;
    }
  }

  /**
   * Overwrites a stored schema body that no longer matches its seed file.
   *
   * <p>Only the body is replaced. {@code status}, {@code publishedAt} and {@code createdBy} are left
   * as they are, so a vertical someone deliberately retired does not get silently reactivated.
   *
   * @return true when the row was rewritten, so the caller evicts the schema cache
   */
  private boolean reconcile(
      VerticalSchemaDocument stored, VerticalSchema fromSeed, Resource resource) throws Exception {
    if (sameBody(stored.getSchema(), fromSeed)) {
      log.debug(
          "vertical_schemas {} v{} matches {} — no change",
          stored.getVerticalId(),
          stored.getVersion(),
          resource.getFilename());
      return false;
    }

    stored.setSchema(fromSeed);
    schemaRepository.save(stored);
    log.info(
        "Reconciled vertical_schemas {} v{} from {} (seed file had drifted)",
        stored.getVerticalId(),
        stored.getVersion(),
        resource.getFilename());
    return true;
  }

  /**
   * Structural comparison via canonical JSON.
   *
   * <p>Uses the serialised form rather than {@code equals} so this does not depend on every nested
   * schema type implementing it — a missed {@code equals} would make the seeder rewrite the row on
   * every boot and evict the cache each time.
   */
  private boolean sameBody(VerticalSchema stored, VerticalSchema fromSeed) throws Exception {
    if (stored == null) {
      return false;
    }
    JsonNode a = objectMapper.valueToTree(stored);
    JsonNode b = objectMapper.valueToTree(fromSeed);
    return a.equals(b);
  }
}
