package com.inventory.app.config;

import com.inventory.app.interceptor.AuthenticationInterceptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class CorsConfig implements WebMvcConfigurer {

  @Value("${client.url}")
  private String crossOriginUrl;

  @Autowired
  private AuthenticationInterceptor authenticationInterceptor;

  @Override
  public void addCorsMappings(CorsRegistry registry) {
    registry.addMapping("/api/**")
        .allowedOrigins(crossOriginUrl)
        .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
        .allowedHeaders("*")
        .allowCredentials(true)
        .maxAge(3600);

    registry.addMapping("/admin/**")
        .allowedOrigins(crossOriginUrl)
        .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
        .allowedHeaders("*")
        .allowCredentials(true)
        .maxAge(3600);

    // Allow mobile upload endpoints from any origin (for QR code scanning)
    registry.addMapping("/m/**")
        .allowedOrigins("*")
        .allowedMethods("GET", "POST", "OPTIONS")
        .allowedHeaders("*")
        .maxAge(3600);
  }

  @Override
  public void addInterceptors(InterceptorRegistry registry) {
    registry.addInterceptor(authenticationInterceptor)
        .addPathPatterns("/api/**", "/admin/**")
        .excludePathPatterns(
            "/api/v1/auth/login",
            "/api/v1/auth/signup",
            "/api/v1/auth/change-password",
            "/api/v1/auth/forgot-password",
            "/api/v1/auth/reset-password",
            "/api/v1/auth/accept-invite",
            "/api/product/get-plugin",
            "/api/product/",
            "/m/**" // Exclude mobile upload endpoints from authentication
        );
  }
}

