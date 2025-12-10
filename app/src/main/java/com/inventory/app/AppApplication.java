package com.inventory.app;

import io.github.cdimascio.dotenv.Dotenv;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.mongodb.repository.config.EnableMongoRepositories;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = {
        "com.inventory.*"
})
@EnableMongoRepositories(basePackages = {
        "com.inventory.product.domain.repository",
        "com.inventory.user.domain.repository",
        "com.inventory.notifications.domain.repository"
})
@EnableAsync
@EnableScheduling
public class AppApplication {

  public static void main(String[] args) {
    // Load .env file if it exists
    try {
      Dotenv dotenv = Dotenv.configure()
              .directory("./")  // Look for .env in project root
              .ignoreIfMissing()  // Don't fail if .env doesn't exist
              .load();
      
      // Set system properties from .env file
      dotenv.entries().forEach(entry -> {
        System.setProperty(entry.getKey(), entry.getValue());
      });
    } catch (Exception e) {
      // If .env file doesn't exist, continue with default values
      System.out.println("No .env file found, using default/system properties");
    }
    
    SpringApplication.run(AppApplication.class, args);
  }

}