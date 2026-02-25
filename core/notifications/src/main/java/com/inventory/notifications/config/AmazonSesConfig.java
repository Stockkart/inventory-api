package com.inventory.notifications.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ses.SesClient;

/**
 * AWS SES configuration for email delivery.
 * Activated when notification.email.provider=ses.
 */
@Slf4j
@Configuration
@ConditionalOnProperty(name = "notification.email.provider", havingValue = "aws-ses")
public class AmazonSesConfig {

  @Value("${aws.region:us-east-1}")
  private String awsRegion;

  @Value("${aws.access-key-id:}")
  private String accessKeyId;

  @Value("${aws.secret-access-key:}")
  private String secretAccessKey;

  @Bean
  public SesClient sesClient() {
    try {
      AwsCredentialsProvider credentialsProvider = getCredentialsProvider();

      SesClient client = SesClient.builder()
          .region(Region.of(awsRegion))
          .credentialsProvider(credentialsProvider)
          .build();

      log.info("AWS SES client configured for region: {}", awsRegion);
      if (StringUtils.hasText(accessKeyId)) {
        log.info("Using explicit AWS credentials for SES");
      } else {
        log.info("Using default AWS credentials provider chain for SES");
      }
      return client;
    } catch (Exception e) {
      log.error("Failed to create AWS SES client: {}", e.getMessage(), e);
      throw new RuntimeException("Failed to initialize AWS SES client", e);
    }
  }

  private AwsCredentialsProvider getCredentialsProvider() {
    if (StringUtils.hasText(accessKeyId) && StringUtils.hasText(secretAccessKey)) {
      return StaticCredentialsProvider.create(
          AwsBasicCredentials.create(accessKeyId, secretAccessKey)
      );
    }
    return DefaultCredentialsProvider.create();
  }
}
