package com.product.service;

import com.product.rest.dto.ProductListDto;
import org.springframework.stereotype.Service;

@Service
public class ProductService {

    public ProductListDto getProducts(){
        return new ProductListDto();
    }
}
