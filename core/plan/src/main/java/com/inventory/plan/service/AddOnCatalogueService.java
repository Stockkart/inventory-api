package com.inventory.plan.service;

import com.inventory.plan.domain.model.AddOn;
import com.inventory.plan.domain.repository.AddOnRepository;
import com.inventory.plan.mapper.AddOnMapper;
import com.inventory.plan.rest.dto.response.AddOnResponse;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** Read side of the add-on catalogue. */
@Service
public class AddOnCatalogueService {

  static final Comparator<AddOn> DISPLAY_ORDER =
      Comparator.comparing(AddOn::getDisplayOrder, Comparator.nullsLast(Comparator.naturalOrder()))
          .thenComparing(AddOn::getCode);

  @Autowired
  private AddOnRepository addOnRepository;

  @Autowired
  private AddOnMapper addOnMapper;

  /** Add-ons on sale, in display order. */
  public List<AddOnResponse> listActive() {
    return activeAddOns().stream().map(addOnMapper::toResponse).toList();
  }

  /** Catalogue rows on sale, in display order. */
  public List<AddOn> activeAddOns() {
    return addOnRepository.findAll().stream()
        .filter(AddOn::isActive)
        .sorted(DISPLAY_ORDER)
        .toList();
  }

  /** The catalogue row as the apps see it, with whether it can be bought ({@code reason} empty = yes). */
  public AddOnResponse toOption(AddOn addOn, java.util.Optional<String> reason) {
    AddOnResponse response = addOnMapper.toResponse(addOn);
    response.setEligible(reason.isEmpty());
    response.setIneligibleReason(reason.orElse(null));
    return response;
  }

  /** Catalogue rows for the given codes, hidden ones included, keyed by code. */
  public Map<String, AddOn> byCode(Collection<String> codes) {
    if (codes.isEmpty()) {
      return Map.of();
    }
    return addOnRepository.findByCodeIn(codes).stream()
        .collect(Collectors.toMap(AddOn::getCode, Function.identity(), (a, b) -> a));
  }
}
