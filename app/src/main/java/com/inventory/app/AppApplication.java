package com.inventory.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.mongodb.repository.config.EnableMongoRepositories;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication(scanBasePackages = {
        "com.inventory.*"
})
@EnableMongoRepositories(basePackages = {
        "com.inventory.product.domain.repository",
        "com.inventory.user.domain.repository",
        "com.inventory.notifications.domain.repository"
})
@EnableAsync
public class AppApplication {

  public static void main(String[] args) {
    SpringApplication.run(AppApplication.class, args);
  }

}