package com.inventory.product.service.vertical;

import com.inventory.common.exception.ValidationException;
import com.inventory.pluginengine.schema.VerticalSchemaStatus;
import com.inventory.product.domain.model.VerticalSchemaDocument;
import com.inventory.product.domain.repository.VerticalSchemaRepository;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Resolves and validates {@code verticalId} / {@code pluginVersion} from {@code vertical_schemas}
 * in MongoDB only — no configured platform default.
 */
@Service
public class VerticalCatalogService {

  public record VerticalPin(String verticalId, String pluginVersion) {}

  private static final String ACTIVE = VerticalSchemaStatus.ACTIVE.name();

  private final VerticalSchemaRepository schemaRepository;

  public VerticalCatalogService(VerticalSchemaRepository schemaRepository) {
    this.schemaRepository = schemaRepository;
  }

  /** Whether an ACTIVE schema row exists for the vertical (any version). */
  public boolean isActiveVertical(String verticalId) {
    if (!StringUtils.hasText(verticalId)) {
      return false;
    }
    return schemaRepository.existsByVerticalIdAndStatus(normalize(verticalId), ACTIVE);
  }

  /** Whether an ACTIVE schema exists for vertical + version. */
  public boolean isActiveVertical(String verticalId, String version) {
    if (!StringUtils.hasText(verticalId) || !StringUtils.hasText(version)) {
      return false;
    }
    return schemaRepository
        .findByVerticalIdAndVersionAndStatus(normalize(verticalId), version.trim(), ACTIVE)
        .isPresent();
  }

  /**
   * Shop registration: {@code verticalId} is required and must match an ACTIVE row in
   * {@code vertical_schemas}.
   */
  public VerticalPin resolveForNewShop(String requestedVerticalId) {
    if (!StringUtils.hasText(requestedVerticalId)) {
      throw new ValidationException(
          "verticalId is required and must exist as ACTIVE in vertical_schemas");
    }
    return pinActiveVertical(normalize(requestedVerticalId));
  }

  /** Legacy backfill: first ACTIVE schema document in DB (order not guaranteed — set explicitly in prod). */
  public VerticalPin resolveFirstActiveVertical() {
    VerticalSchemaDocument doc =
        schemaRepository.findByStatus(ACTIVE).stream()
            .findFirst()
            .orElseThrow(
                () -> new ValidationException("No ACTIVE vertical found in vertical_schemas"));
    return new VerticalPin(normalize(doc.getVerticalId()), doc.getVersion());
  }

  private VerticalPin pinActiveVertical(String verticalId) {
    VerticalSchemaDocument doc =
        schemaRepository.findByVerticalIdAndStatus(verticalId, ACTIVE).stream()
            .findFirst()
            .orElseThrow(
                () ->
                    new ValidationException(
                        "Vertical not available in vertical_schemas (ACTIVE): " + verticalId));
    return new VerticalPin(normalize(doc.getVerticalId()), doc.getVersion());
  }

  private static String normalize(String verticalId) {
    return verticalId.trim().toLowerCase();
  }
}
