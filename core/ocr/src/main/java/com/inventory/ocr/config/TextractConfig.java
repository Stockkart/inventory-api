package com.inventory.ocr.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.textract.TextractClient;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;

/**
 * Configuration class for AWS Textract setup.
 * Handles Textract client initialization and configuration.
 * Supports multiple credential methods:
 * 1. Explicit credentials from application.properties (aws.access-key-id and aws.secret-access-key)
 * 2. Environment variables (AWS_ACCESS_KEY_ID and AWS_SECRET_ACCESS_KEY)
 * 3. AWS credentials file (~/.aws/credentials)
 * 4. IAM role (when running on EC2/ECS)
 */
@Configuration
@Slf4j
public class TextractConfig {

  @Value("${aws.region:us-east-1}")
  private String awsRegion;

  @Value("${aws.access-key-id:}")
  private String accessKeyId;

  @Value("${aws.secret-access-key:}")
  private String secretAccessKey;

  /**
   * Creates and configures an AWS Textract client.
   * 
   * @return configured TextractClient bean
   */
  @Bean
  public TextractClient textractClient() {
    try {
      // Use explicit credentials if provided, otherwise use default credential chain
      AwsCredentialsProvider credentialsProvider = getCredentialsProvider();

      TextractClient client = TextractClient.builder()
          .region(Region.of(awsRegion))
          .credentialsProvider(credentialsProvider)
          .build();

      log.info("AWS Textract client configured for region: {}", awsRegion);
      if (StringUtils.hasText(accessKeyId)) {
        log.info("Using explicit AWS credentials from application properties");
      } else {
        log.info("Using default AWS credentials provider chain (env vars, credentials file, or IAM role)");
      }

      return client;
    } catch (Exception e) {
      log.error("Failed to create AWS Textract client: {}", e.getMessage(), e);
      throw new RuntimeException("Failed to initialize AWS Textract client", e);
    }
  }

  /**
   * Gets the appropriate credentials provider based on configuration.
   * 
   * @return AwsCredentialsProvider
   */
  private AwsCredentialsProvider getCredentialsProvider() {
    // If explicit credentials are provided in application.properties, use them
    if (StringUtils.hasText(accessKeyId) && StringUtils.hasText(secretAccessKey)) {
      log.info("Using explicit AWS credentials from application properties");
      return StaticCredentialsProvider.create(
          AwsBasicCredentials.create(accessKeyId, secretAccessKey)
      );
    }

    // Otherwise, use the default credential chain
    // This will check in order:
    // 1. Environment variables (AWS_ACCESS_KEY_ID, AWS_SECRET_ACCESS_KEY)
    // 2. Java system properties
    // 3. Web identity token from EKS/IRSA
    // 4. Credentials file (~/.aws/credentials)
    // 5. Container credentials (ECS)
    // 6. Instance profile credentials (EC2)
    log.info("Using default AWS credentials provider chain");
    return DefaultCredentialsProvider.create();
  }
}
