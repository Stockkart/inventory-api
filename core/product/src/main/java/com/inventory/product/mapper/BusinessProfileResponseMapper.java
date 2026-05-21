package com.inventory.product.mapper;

import com.inventory.pluginengine.profile.BusinessProfile;
import com.inventory.pluginengine.profile.EntityProfile;
import com.inventory.pluginengine.profile.FieldDefinition;
import com.inventory.pluginengine.profile.ProfileUi;
import com.inventory.product.rest.dto.response.BusinessProfileResponse;
import org.mapstruct.Mapper;
import org.mapstruct.ReportingPolicy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface BusinessProfileResponseMapper {

  default BusinessProfileResponse toResponse(BusinessProfile profile) {
    if (profile == null) {
      return null;
    }
    Map<String, BusinessProfileResponse.EntityProfileResponse> entities = new LinkedHashMap<>();
    profile.getEntities().forEach((key, entity) -> entities.put(key, toEntityResponse(entity)));
    return BusinessProfileResponse.builder()
        .id(profile.getId())
        .code(profile.getCode())
        .name(profile.getName())
        .version(profile.getVersion())
        .modules(profile.getModules())
        .entities(entities)
        .strategies(profile.getStrategies())
        .pricing(profile.getPricing())
        .ui(toUiResponse(profile.getUi()))
        .build();
  }

  default BusinessProfileResponse.EntityProfileResponse toEntityResponse(EntityProfile entity) {
    List<BusinessProfileResponse.FieldDefinitionResponse> fields = entity.getFields().stream()
        .map(this::toFieldResponse)
        .collect(Collectors.toList());
    return BusinessProfileResponse.EntityProfileResponse.builder().fields(fields).build();
  }

  default BusinessProfileResponse.FieldDefinitionResponse toFieldResponse(FieldDefinition field) {
    return BusinessProfileResponse.FieldDefinitionResponse.builder()
        .key(field.getKey())
        .required(field.getRequired())
        .visible(field.getVisible())
        .storage(field.getStorage())
        .label(field.getLabel())
        .build();
  }

  default BusinessProfileResponse.ProfileUiResponse toUiResponse(ProfileUi ui) {
    return BusinessProfileResponse.ProfileUiResponse.builder()
        .navHidden(ui.getNavHidden())
        .build();
  }
}
