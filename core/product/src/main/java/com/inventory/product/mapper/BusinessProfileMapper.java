package com.inventory.product.mapper;

import com.inventory.pluginengine.profile.BusinessProfile;
import com.inventory.pluginengine.profile.EntityProfile;
import com.inventory.pluginengine.profile.FieldDefinition;
import com.inventory.pluginengine.profile.ProfileUi;
import com.inventory.product.domain.model.BusinessType;
import com.inventory.product.domain.model.profile.EntityProfileDoc;
import com.inventory.product.domain.model.profile.FieldDefinitionDoc;
import com.inventory.product.domain.model.profile.UiProfileDoc;
import org.mapstruct.Mapper;
import org.mapstruct.ReportingPolicy;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface BusinessProfileMapper {

  default BusinessProfile toProfile(BusinessType doc) {
    if (doc == null) {
      return null;
    }
    String id = StringUtils.hasText(doc.getId()) ? doc.getId() : doc.getCode();
    return BusinessProfile.builder()
        .id(id)
        .code(StringUtils.hasText(doc.getCode()) ? doc.getCode() : id)
        .name(doc.getName())
        .enabled(doc.isEnabled())
        .version(doc.getVersion())
        .modules(doc.getModules())
        .entities(toEntityProfiles(doc.getEntities()))
        .strategies(doc.getStrategies())
        .pricing(doc.getPricing())
        .ui(toProfileUi(doc.getUi()))
        .build();
  }

  default Map<String, EntityProfile> toEntityProfiles(Map<String, EntityProfileDoc> entities) {
    if (entities == null || entities.isEmpty()) {
      return Map.of();
    }
    Map<String, EntityProfile> result = new LinkedHashMap<>();
    entities.forEach((key, doc) -> result.put(key, toEntityProfile(doc)));
    return result;
  }

  default EntityProfile toEntityProfile(EntityProfileDoc doc) {
    if (doc == null || doc.getFields() == null) {
      return EntityProfile.builder().fields(List.of()).build();
    }
    List<FieldDefinition> fields = doc.getFields().stream()
        .map(this::toFieldDefinition)
        .collect(Collectors.toList());
    return EntityProfile.builder().fields(fields).build();
  }

  default FieldDefinition toFieldDefinition(FieldDefinitionDoc doc) {
    if (doc == null) {
      return null;
    }
    return FieldDefinition.builder()
        .key(doc.getKey())
        .required(doc.getRequired())
        .visible(doc.getVisible())
        .storage(doc.getStorage())
        .label(doc.getLabel())
        .build();
  }

  default ProfileUi toProfileUi(UiProfileDoc doc) {
    if (doc == null) {
      return ProfileUi.builder().build();
    }
    return ProfileUi.builder().navHidden(doc.getNavHidden()).build();
  }
}
