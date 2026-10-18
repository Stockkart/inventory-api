package com.inventory.product.mapper;

import com.inventory.pricing.domain.model.Scheme;
import com.inventory.pricing.domain.model.RateRuleSpec;
import com.inventory.pricing.rest.dto.response.SchemeDto;
import com.inventory.product.domain.model.RateRule;
import com.inventory.product.domain.model.enums.RateBase;
import com.inventory.product.rest.dto.response.RateRuleDto;
import java.util.List;
import org.mapstruct.Mapper;
import org.mapstruct.ReportingPolicy;
import org.springframework.util.StringUtils;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface RateRulesMapper {

  RateRuleDto toDto(RateRule rule);

  List<RateRuleDto> toDtos(List<RateRule> rules);

  RateRule toEntity(RateRuleDto dto);

  List<RateRule> toEntities(List<RateRuleDto> dtos);

  Scheme toScheme(SchemeDto dto);

  SchemeDto toSchemeDto(Scheme scheme);

  default String baseToWire(RateBase base) {
    return base == null ? null : base.value();
  }

  default RateBase baseFromWire(String wire) {
    return RateBase.fromValue(wire);
  }

  /** A saved or previewed rule as the calculator reads it; the name is trimmed. */
  default RateRuleSpec toSpec(RateRule rule) {
    return new RateRuleSpec(
        StringUtils.hasText(rule.getName()) ? rule.getName().trim() : rule.getName(),
        baseToWire(rule.getBase()), rule.getDiscountPercent(), rule.getScheme());
  }
}
