package com.inventory.product.service.profile;

import com.inventory.product.domain.model.BusinessType;
import com.inventory.product.domain.repository.BusinessTypeRepository;
import com.inventory.product.mapper.BusinessProfileResponseMapper;
import com.inventory.product.rest.dto.response.BusinessProfileOptionResponse;
import com.inventory.product.rest.dto.response.BusinessProfileResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;

@Service
@Slf4j
@Transactional(readOnly = true)
public class BusinessProfileService {

  @Autowired
  private ProfileResolver profileResolver;

  @Autowired
  private BusinessTypeRepository businessTypeRepository;

  @Autowired
  private BusinessProfileResponseMapper responseMapper;

  public BusinessProfileResponse getProfileForShop(String shopId) {
    return responseMapper.toResponse(profileResolver.resolveForShop(shopId));
  }

  public List<BusinessProfileOptionResponse> listEnabledProfileOptions() {
    return businessTypeRepository.findAll().stream()
        .filter(BusinessType::isEnabled)
        .sorted(Comparator.comparing(BusinessType::getName, Comparator.nullsLast(String::compareToIgnoreCase)))
        .map(doc -> new BusinessProfileOptionResponse(doc.getId(), doc.getName()))
        .toList();
  }
}
