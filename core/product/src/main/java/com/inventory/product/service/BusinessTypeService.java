package com.inventory.product.service;

import com.inventory.product.domain.model.BusinessType;
import com.inventory.product.domain.repository.BusinessTypeRepository;
import com.inventory.product.rest.dto.business.BusinessTypeResponse;
import com.inventory.product.rest.dto.business.CreateBusinessTypeRequest;
import com.inventory.product.rest.mapper.BusinessTypeMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class BusinessTypeService {

    private final BusinessTypeRepository repository;
    private final BusinessTypeMapper mapper;

    public BusinessTypeResponse create(CreateBusinessTypeRequest request) {
        BusinessType entity = mapper.toEntity(request);
        repository.save(entity);
        return mapper.toResponse(entity);
    }
}

