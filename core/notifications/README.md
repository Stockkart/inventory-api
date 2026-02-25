# Notification Service Module

Messaging and notification system for StockKart supporting Email, SMS, and WhatsApp.

## Architecture

- **NotificationService**: Orchestrates sends, no provider-specific logic
- **Providers**: Interface-driven (EmailProvider, SmsProvider, WhatsAppProvider)
- **RetryScheduler**: Polls failed jobs and retries with exponential backoff
- **TemplateEngine**: Variable substitution for notification content

## Usage

### From Domain Services

Inject `NotificationService` and call `sendAsync`:

```java
@Autowired
private NotificationService notificationService;

// Password reset email
notificationService.sendAsync(
    NotificationType.PASSWORD_RESET,
    NotificationChannel.EMAIL,
    NotificationRecipient.builder().email("user@example.com").build(),
    "password_reset",
    Map.of("subject", "Reset your password", "body", "Click here: {{resetLink}}", "resetLink", "https://...")
);

// OTP SMS
notificationService.sendAsync(
    NotificationType.OTP,
    NotificationChannel.SMS,
    NotificationRecipient.builder().phone("+1234567890").build(),
    "otp",
    Map.of("body", "Your OTP is {{code}}", "code", "123456")
);
```

### REST API

POST `/api/v1/notifications/send`:

```json
{
  "type": "PASSWORD_RESET",
  "channel": "EMAIL",
  "email": "user@example.com",
  "templateId": "password_reset",
  "variables": {
    "subject": "Reset your password",
    "body": "Click: {{link}}",
    "link": "https://..."
  }
}
```

## Configuration

| Property | Default | Description |
|----------|---------|-------------|
| notification.retry.base-delay-minutes | 5 | Base delay for exponential backoff |
| notification.retry.max-retries | 3 | Max retries per job |
| notification.retry.interval-ms | 60000 | Scheduler poll interval |
| notification.retry.batch-size | 50 | Max jobs per retry batch |
| notification.email.provider | noop | noop \| ses |
| notification.email.from | - | Verified sender (required for SES) |
| notification.sms.provider | noop | noop \| twilio |
| notification.whatsapp.provider | noop | noop \| meta \| twilio |

## Amazon SES

1. Set `notification.email.provider=ses`
2. Set `NOTIFICATION_EMAIL_FROM` to a verified email/domain in SES
3. Use existing AWS credentials (AWS_ACCESS_KEY, AWS_SECRET_ACCESS, AWS_REGION)

## Adding More Providers

1. Create implementation (e.g. `SendGridEmailProvider`) implementing `EmailProvider`
2. Add `@ConditionalOnProperty(name = "notification.email.provider", havingValue = "sendgrid")`
3. Add required API key to `.env` and reference in properties
