package com.inventory.product.service.vertical;

import com.inventory.common.exception.ResourceNotFoundException;
import com.inventory.common.exception.ValidationException;
import com.inventory.pluginengine.schema.SchemaDisplayMode;
import com.inventory.pluginengine.schema.SchemaFieldFilter;
import com.inventory.pluginengine.schema.VerticalEntitySchema;
import com.inventory.pluginengine.schema.VerticalSchema;
import com.inventory.pluginengine.schema.VerticalSchemaField;
import com.inventory.product.domain.model.Shop;
import com.inventory.product.domain.model.VerticalSchemaDocument;
import com.inventory.product.domain.repository.ShopRepository;
import com.inventory.product.domain.repository.VerticalSchemaRepository;
import com.inventory.product.rest.dto.response.ShopSchemaResponse;
import com.inventory.product.rest.dto.response.VerticalSummaryResponse;
import com.inventory.product.validation.ShopValidator;
import com.inventory.user.service.UserShopMembershipService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@Slf4j
@Transactional(readOnly = true)
public class VerticalSchemaService {

  private final VerticalSchemaRepository schemaRepository;
  private final ShopRepository shopRepository;
  private final SchemaLoader schemaLoader;
  private final ShopValidator shopValidator;
  private final UserShopMembershipService membershipService;

  public VerticalSchemaService(
      VerticalSchemaRepository schemaRepository,
      ShopRepository shopRepository,
      SchemaLoader schemaLoader,
      ShopValidator shopValidator,
      UserShopMembershipService membershipService) {
    this.schemaRepository = schemaRepository;
    this.shopRepository = shopRepository;
    this.schemaLoader = schemaLoader;
    this.shopValidator = shopValidator;
    this.membershipService = membershipService;
  }

  public List<VerticalSummaryResponse> listActiveVerticals() {
    return schemaRepository.findByStatus("ACTIVE").stream()
        .map(this::toSummary)
        .collect(Collectors.toList());
  }

  public ShopSchemaResponse getShopSchema(String shopId, String userId, String modeQuery) {
    shopValidator.validateShopAccess(membershipService.hasAccess(userId, shopId));
    Shop shop =
        shopRepository
            .findById(shopId)
            .orElseThrow(() -> new ResourceNotFoundException("Shop", "shopId", shopId));

    if (!StringUtils.hasText(shop.getVerticalId())) {
      throw new ValidationException("Shop has no vertical configured");
    }
    String pluginVersion = shop.getPluginVersion();
    VerticalSchema schema = schemaLoader.load(shop.getVerticalId(), pluginVersion);
    SchemaDisplayMode mode = SchemaDisplayMode.fromQuery(modeQuery);

    Map<String, VerticalEntitySchema> filteredEntities = new LinkedHashMap<>();
    if (schema.getEntities() != null) {
      schema.getEntities().forEach((entityName, entitySchema) -> {
        List<VerticalSchemaField> filtered =
            SchemaFieldFilter.filterForMode(entitySchema.getFields(), mode);
        VerticalEntitySchema copy = new VerticalEntitySchema();
        copy.setFields(filtered);
        filteredEntities.put(entityName, copy);
      });
    }

    ShopSchemaResponse response = new ShopSchemaResponse();
    response.setShopId(shopId);
    response.setVerticalId(shop.getVerticalId());
    response.setPluginVersion(pluginVersion);
    response.setMode(mode.name().toLowerCase());
    response.setEntities(filteredEntities);
    return response;
  }

  private VerticalSummaryResponse toSummary(VerticalSchemaDocument doc) {
    return new VerticalSummaryResponse(doc.getVerticalId(), doc.getVersion(), doc.getStatus());
  }
}
