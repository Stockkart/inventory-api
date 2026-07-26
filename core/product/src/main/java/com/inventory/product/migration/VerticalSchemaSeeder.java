package com.inventory.product.migration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.inventory.pluginengine.schema.VerticalSchemaStatus;
import com.inventory.pluginengine.schema.VerticalSchema;
import com.inventory.product.domain.model.VerticalSchemaDocument;
import com.inventory.product.domain.repository.VerticalSchemaRepository;
import com.inventory.product.service.vertical.SchemaLoader;
import java.io.IOException;
import java.time.Instant;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

/**
 * Seeds {@code vertical_schemas} from {@code classpath:seeds/*.json}.
 * Inserts when missing; updates schema body when the same vertical+version already exists
 * so seed file edits (e.g. new medical fields) apply on restart.
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
    boolean anyInserted = false;
    try {
      Resource[] resources = new PathMatchingResourcePatternResolver().getResources(SEED_PATTERN);
      for (Resource resource : resources) {
        if (seedResource(resource)) {
          anyInserted = true;
        }
      }
    } catch (IOException e) {
      log.error("Failed to scan {}: {}", SEED_PATTERN, e.getMessage(), e);
    }
    if (anyInserted) {
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
      var existing = schemaRepository.findByVerticalIdAndVersion(verticalId, version);
      if (existing.isPresent()) {
        VerticalSchemaDocument doc = existing.get();
        doc.setSchema(schema);
        doc.setPublishedAt(Instant.now());
        schemaRepository.save(doc);
        log.info("Updated vertical_schemas {} from {}", doc.getId(), resource.getFilename());
        return true;
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
}
