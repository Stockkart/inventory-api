package com.inventory.product.service;

import com.inventory.pluginengine.PluginManager;
import com.inventory.product.domain.model.Product;
import com.inventory.product.domain.repository.ProductRepository;
import com.inventory.product.rest.dto.ProductListDto;
import com.inventory.product.rest.dto.product.ProductListResponse;
import com.inventory.product.rest.dto.product.ProductResponse;
import com.inventory.product.rest.mapper.ProductMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class ProductService {

    private final PluginManager pluginManager;
    private final ProductRepository productRepository;
    private final ProductMapper productMapper;

    public ProductListDto getProducts(){
        return new ProductListDto();
    }

    public String getCurrentPlugin(){
        return pluginManager.getCurrentPlugin();
    }

    public ProductResponse getProduct(String barcode) {
        Product product = productRepository.findById(barcode)
                .orElseThrow(() -> new IllegalArgumentException("Product not found"));
        return productMapper.toResponse(product);
    }

    public ProductListResponse searchProducts(String query) {
        List<ProductResponse> responses = (query == null || query.isBlank()
                ? productRepository.findAll()
                : productRepository.findByNameContainingIgnoreCase(query))
                .stream()
                .map(productMapper::toResponse)
                .toList();
        return ProductListResponse.builder().data(responses).build();
    }
}
