package com.inventory.product.service;

import com.inventory.pluginengine.PluginManager;
import com.inventory.product.rest.dto.ProductListDto;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class ProductService {

    @Autowired
    private PluginManager pluginManager;

    public ProductListDto getProducts(){
        return new ProductListDto();
    }

    public String getCurrentPlugin(){
        return pluginManager.getCurrentPlugin();
    }
}
