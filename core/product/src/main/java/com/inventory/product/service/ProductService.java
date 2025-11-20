package com.inventory.product.service;

import com.inventory.pluginengine.PluginManager;
import com.inventory.product.domain.model.Product;
import com.inventory.product.domain.repository.ProductRepository;
import com.inventory.product.rest.dto.ProductListDto;
import com.inventory.product.rest.dto.product.ProductListResponse;
import com.inventory.product.rest.dto.product.ProductResponse;
import com.inventory.product.rest.mapper.ProductMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class ProductService {

    @Autowired
    private PluginManager pluginManager;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private ProductMapper productMapper;

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
