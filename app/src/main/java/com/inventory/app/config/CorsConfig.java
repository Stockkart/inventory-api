package com.inventory.app.config;

import com.inventory.app.interceptor.AuthenticationInterceptor;
import com.inventory.app.interceptor.PlanExpiryInterceptor;
import com.inventory.app.interceptor.RbacModuleInterceptor;
import com.inventory.hr.service.AttendanceAppAuthInterceptor;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class CorsConfig implements WebMvcConfigurer {

  /**
   * Paths outside the Bearer chain (authentication, RBAC, plan expiry). Mirrors {@code
   * AuthenticationInterceptor.PUBLIC_ENDPOINTS} / {@code PUBLIC_ENDPOINT_PATTERNS} (AGENTS X3).
   */
  private static final List<String> PUBLIC_PATHS = List.of(
      "/api/v1/auth/login",
      "/api/v1/auth/signup",
      "/api/v1/auth/forgot-password",
      "/api/v1/auth/reset-password",
      "/api/v1/auth/accept-invite",
      "/api/product/get-plugin",
      "/api/product/",
      "/m/**" // Exclude mobile upload endpoints from authentication
  );

  @Value("${client.url}")
  private String crossOriginUrl;

  @Autowired
  private AuthenticationInterceptor authenticationInterceptor;

  @Autowired
  private PlanExpiryInterceptor planExpiryInterceptor;

  @Autowired
  private RbacModuleInterceptor rbacModuleInterceptor;

  @Autowired
  private AttendanceAppAuthInterceptor attendanceAppAuthInterceptor;

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
    // The Attendance app has no login (HR D7): its device-facing paths leave the Bearer chain and
    // are authenticated by the signed request envelope below. Health (/attendance-app/health) is
    // not a device path and stays on the Bearer chain; the device paths are listed one by one for
    // that reason.
    String[] excluded = bearerChainExclusions();

    registry.addInterceptor(authenticationInterceptor)
        .addPathPatterns("/api/**", "/admin/**")
        .excludePathPatterns(excluded);

    registry.addInterceptor(rbacModuleInterceptor)
        .addPathPatterns("/api/**")
        .excludePathPatterns(excluded);

    registry.addInterceptor(planExpiryInterceptor)
        .addPathPatterns("/api/**")
        .excludePathPatterns(excluded);

    registry.addInterceptor(attendanceAppAuthInterceptor)
        .addPathPatterns(AttendanceAppAuthInterceptor.PATH_PATTERN)
        .excludePathPatterns(AttendanceAppAuthInterceptor.HEALTH_PATH);
  }

  private static String[] bearerChainExclusions() {
    List<String> paths = new ArrayList<>(PUBLIC_PATHS);
    paths.addAll(AttendanceAppAuthInterceptor.DEVICE_PATH_PATTERNS);
    return paths.toArray(String[]::new);
  }
}
