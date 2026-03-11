package com.inventory.metrics.config;

import com.inventory.metrics.interceptor.StatusCodesMetricsInterceptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class MetricsWebConfig implements WebMvcConfigurer {

  @Autowired
  private StatusCodesMetricsInterceptor statusCodesMetricsInterceptor;

  @Override
  public void addInterceptors(InterceptorRegistry registry) {
    registry.addInterceptor(statusCodesMetricsInterceptor)
        .addPathPatterns("/api/**", "/admin/**", "/m/**");
  }
}
