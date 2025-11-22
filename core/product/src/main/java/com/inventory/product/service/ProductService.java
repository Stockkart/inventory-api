package com.inventory.product.service;

import com.inventory.common.constants.ErrorCode;
import com.inventory.common.exception.BaseException;
import com.inventory.common.exception.ResourceNotFoundException;
import com.inventory.common.exception.ValidationException;
import com.inventory.product.validation.ProductValidator;
import com.inventory.pluginengine.PluginManager;
import com.inventory.product.domain.model.Product;
import com.inventory.product.domain.repository.ProductRepository;
import com.inventory.product.rest.dto.ProductListDto;
import com.inventory.product.rest.dto.product.ProductListResponse;
import com.inventory.product.rest.dto.product.ProductResponse;
import com.inventory.product.rest.mapper.ProductMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Service
@Transactional(readOnly = true)
public class ProductService {

    @Autowired
    private PluginManager pluginManager;

    @Autowired
    private ProductRepository productRepository;
    
    @Autowired
    private ProductValidator productValidator;

    @Autowired
    private ProductMapper productMapper;

    public ProductListDto getProducts(){
        try {
            log.debug("Retrieving all products");
            return new ProductListDto();
        } catch (Exception e) {
            log.error("Unexpected error while retrieving products: {}", e.getMessage(), e);
            throw new BaseException(ErrorCode.INTERNAL_SERVER_ERROR, "Failed to retrieve products");
        }
    }

    public String getCurrentPlugin(){
        try {
            log.debug("Retrieving current plugin");
            return pluginManager.getCurrentPlugin();
        } catch (RuntimeException e) {
            log.error("Error while retrieving current plugin: {}", e.getMessage(), e);
            throw new BaseException(ErrorCode.INTERNAL_SERVER_ERROR, "Failed to retrieve current plugin: " + e.getMessage());
        }
    }

    public ProductResponse getProduct(String barcode) {
        try {
            // Validate barcode using ProductValidator
            productValidator.validateBarcode(barcode);
            
            log.debug("Retrieving product with barcode: {}", barcode);
            Product product = productRepository.findById(barcode)
                    .orElseThrow(() -> new ResourceNotFoundException("Product", "barcode", barcode));
                    
            return productMapper.toResponse(product);
            
        } catch (ResourceNotFoundException | ValidationException e) {
            log.warn("Failed to get product: {}", e.getMessage());
            throw e;
        } catch (DataAccessException e) {
            log.error("Database error while retrieving product with barcode {}: {}", barcode, e.getMessage(), e);
            throw new BaseException(ErrorCode.INTERNAL_SERVER_ERROR, "Error retrieving product");
        } catch (Exception e) {
            log.error("Unexpected error while retrieving product with barcode {}: {}", barcode, e.getMessage(), e);
            throw new BaseException(ErrorCode.INTERNAL_SERVER_ERROR, "Failed to retrieve product");
        }
    }

    public ProductListResponse searchProducts(String query) {
        try {
            log.debug("Searching products with query: {}", query);
            
            List<ProductResponse> responses = (query == null || query.isBlank()
                    ? productRepository.findAll()
                    : productRepository.findByNameContainingIgnoreCase(query))
                    .stream()
                    .map(product -> {
                        try {
                            return productMapper.toResponse(product);
                        } catch (Exception e) {
                            log.error("Error mapping product with id {}: {}", product.getBarcode(), e.getMessage(), e);
                            throw new BaseException(ErrorCode.INTERNAL_SERVER_ERROR, "Error processing product data");
                        }
                    })
                    .toList();
                    
            return ProductListResponse.builder()
                    .data(responses)
                    .build();
            
        } catch (DataAccessException e) {
            log.error("Database error while searching products with query '{}': {}", query, e.getMessage(), e);
            throw new BaseException(ErrorCode.INTERNAL_SERVER_ERROR, "Error searching products");
        } catch (BaseException e) {
            // Re-throw already wrapped exceptions
            throw e;
        } catch (Exception e) {
            log.error("Unexpected error while searching products with query '{}': {}", query, e.getMessage(), e);
            throw new BaseException(ErrorCode.INTERNAL_SERVER_ERROR, "Failed to search products");
        }
    }
}
