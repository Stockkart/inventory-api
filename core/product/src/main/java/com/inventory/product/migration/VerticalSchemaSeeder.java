package com.inventory.product.migration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.inventory.pluginengine.VerticalConstants;
import com.inventory.pluginengine.schema.VerticalSchema;
import com.inventory.product.domain.model.VerticalSchemaDocument;
import com.inventory.product.domain.repository.VerticalSchemaRepository;
import com.inventory.product.service.vertical.SchemaLoader;
import java.time.Instant;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * Seeds {@code vertical_schemas} from classpath JSON when no row exists for each vertical version.
 * Runtime always reads schema from MongoDB — not from these files.
 */
@Component
@Slf4j
public class VerticalSchemaSeeder {

  private record SeedSpec(String verticalId, String version, String resource, String documentId) {}

  private static final List<SeedSpec> SEEDS =
      List.of(
          new SeedSpec(
              VerticalConstants.MEDICAL,
              VerticalConstants.DEFAULT_PLUGIN_VERSION,
              "seeds/medical-v1.json",
              "medical_1.0.0"),
          new SeedSpec(
              VerticalConstants.SPORTS,
              VerticalConstants.DEFAULT_PLUGIN_VERSION,
              "seeds/sports-v1.json",
              "sports_1.0.0"));

  @Autowired private VerticalSchemaRepository schemaRepository;
  @Autowired private SchemaLoader schemaLoader;
  @Autowired private ObjectMapper objectMapper;

  @EventListener(ApplicationReadyEvent.class)
  @Order(10)
  public void seedOnStartup() {
    boolean anyInserted = false;
    for (SeedSpec seed : SEEDS) {
      try {
        if (schemaRepository
            .findByVerticalIdAndVersion(seed.verticalId(), seed.version())
            .isPresent()) {
          log.debug(
              "vertical_schemas already contains {} v{} — skip seed",
              seed.verticalId(),
              seed.version());
          continue;
        }
        ClassPathResource resource = new ClassPathResource(seed.resource());
        VerticalSchema schema =
            objectMapper.readValue(resource.getInputStream(), VerticalSchema.class);
        VerticalSchemaDocument doc = new VerticalSchemaDocument();
        doc.setId(seed.documentId());
        doc.setVerticalId(seed.verticalId());
        doc.setVersion(seed.version());
        doc.setStatus(VerticalConstants.SCHEMA_STATUS_ACTIVE);
        doc.setSchema(schema);
        doc.setPublishedAt(Instant.now());
        doc.setCreatedBy("seed:" + seed.resource());
        schemaRepository.save(doc);
        anyInserted = true;
        log.info(
            "Seeded vertical_schemas document {} from {} ({})",
            seed.documentId(),
            seed.resource(),
            seed.verticalId());
      } catch (Exception e) {
        log.error(
            "Failed to seed vertical_schemas from {} ({}): {}",
            seed.resource(),
            seed.verticalId(),
            e.getMessage(),
            e);
      }
    }
    if (anyInserted) {
      schemaLoader.evictCache();
    }
  }
}
