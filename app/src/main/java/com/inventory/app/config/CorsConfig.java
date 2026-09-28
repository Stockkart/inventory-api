package com.inventory.app.config;

import com.inventory.app.interceptor.AdminAuthenticationInterceptor;
import com.inventory.app.interceptor.AuthenticationInterceptor;
import com.inventory.app.interceptor.EntitlementInterceptor;
import com.inventory.app.interceptor.PlanExpiryInterceptor;
import com.inventory.app.interceptor.RbacModuleInterceptor;
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

  /** Origin of the admin app; admin paths accept only this origin. Empty blocks cross-origin admin calls. */
  @Value("${admin.client.url:}")
  private String adminOriginUrl;

  @Autowired
  private AuthenticationInterceptor authenticationInterceptor;

  @Autowired
  private PlanExpiryInterceptor planExpiryInterceptor;

  @Autowired
  private RbacModuleInterceptor rbacModuleInterceptor;

  @Autowired
  private AdminAuthenticationInterceptor adminAuthenticationInterceptor;

  @Autowired
  private EntitlementInterceptor entitlementInterceptor;

  @Override
  public void addCorsMappings(CorsRegistry registry) {
    // Registered first: the first matching mapping wins.
    for (String adminPath : AdminAuthenticationInterceptor.ADMIN_PATH_PATTERNS) {
      registry.addMapping(adminPath)
          .allowedOrigins(adminOriginUrl.isBlank() ? new String[0] : new String[] {adminOriginUrl})
          .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
          .allowedHeaders("*")
          .allowCredentials(true)
          .maxAge(3600);
    }

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
    registry.addInterceptor(adminAuthenticationInterceptor)
        .addPathPatterns(AdminAuthenticationInterceptor.ADMIN_PATH_PATTERNS)
        .excludePathPatterns(AdminAuthenticationInterceptor.LOGIN_PATH);

    registry.addInterceptor(authenticationInterceptor)
        .addPathPatterns("/api/**", "/admin/**")
        .excludePathPatterns(AdminAuthenticationInterceptor.ADMIN_PATH_PATTERNS)
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

    registry.addInterceptor(rbacModuleInterceptor)
        .addPathPatterns("/api/**")
        .excludePathPatterns(AdminAuthenticationInterceptor.ADMIN_PATH_PATTERNS)
        .excludePathPatterns(
            "/api/v1/auth/login",
            "/api/v1/auth/signup",
            "/api/v1/auth/change-password",
            "/api/v1/auth/forgot-password",
            "/api/v1/auth/reset-password",
            "/api/v1/auth/accept-invite",
            "/api/product/get-plugin",
            "/api/product/",
            "/m/**"
        );

    registry.addInterceptor(planExpiryInterceptor)
        .addPathPatterns("/api/**")
        .excludePathPatterns(AdminAuthenticationInterceptor.ADMIN_PATH_PATTERNS)
        .excludePathPatterns(
            "/api/v1/auth/login",
            "/api/v1/auth/signup",
            "/api/v1/auth/change-password",
            "/api/v1/auth/forgot-password",
            "/api/v1/auth/reset-password",
            "/api/v1/auth/accept-invite",
            "/api/product/get-plugin",
            "/api/product/",
            "/m/**"
        );

    registry.addInterceptor(entitlementInterceptor)
        .addPathPatterns("/api/**")
        .excludePathPatterns(AdminAuthenticationInterceptor.ADMIN_PATH_PATTERNS);
  }
}

