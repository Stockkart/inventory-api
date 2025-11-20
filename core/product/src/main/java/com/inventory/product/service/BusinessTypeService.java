package com.inventory.product.service;

import com.inventory.product.domain.model.BusinessType;
import com.inventory.product.domain.repository.BusinessTypeRepository;
import com.inventory.product.rest.dto.business.BusinessTypeResponse;
import com.inventory.product.rest.dto.business.CreateBusinessTypeRequest;
import com.inventory.product.rest.mapper.BusinessTypeMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class BusinessTypeService {

    @Autowired
    private BusinessTypeRepository repository;

    @Autowired
    private BusinessTypeMapper mapper;

    public BusinessTypeResponse create(CreateBusinessTypeRequest request) {
        BusinessType entity = mapper.toEntity(request);
        repository.save(entity);
        return mapper.toResponse(entity);
    }
}

