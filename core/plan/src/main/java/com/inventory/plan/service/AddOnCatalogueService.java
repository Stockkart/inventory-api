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
    return addOnRepository.findAll().stream()
        .filter(AddOn::isActive)
        .sorted(DISPLAY_ORDER)
        .map(addOnMapper::toResponse)
        .toList();
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
