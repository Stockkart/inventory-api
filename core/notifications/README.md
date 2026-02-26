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
| notification.email.provider | noop | noop \| resend \| aws-ses |
| notification.email.from | - | Sender address (required for resend/aws-ses) |
| notification.email.api-key | - | Resend API key (when provider=resend); or set RESEND_API_KEY |
| notification.sms.provider | noop | noop \| twilio |
| notification.whatsapp.provider | noop | noop \| meta |
| notification.whatsapp.api-token | - | Meta WhatsApp Cloud API access token |
| notification.whatsapp.phone-number-id | - | Meta WhatsApp phone number id |
| notification.whatsapp.login-to | - | Test phone to receive login success WhatsApp |

## Resend.com

1. Set `notification.email.provider=resend`
2. Set `RESEND_API_KEY` (from [Resend dashboard](https://resend.com/api-keys))
3. Set `NOTIFICATION_EMAIL_FROM` to a verified domain in Resend (e.g. `onboarding@resend.dev` for testing)

## Amazon SES

1. Set `notification.email.provider=aws-ses`
2. Set `NOTIFICATION_EMAIL_FROM` to a verified email/domain in SES
3. Use existing AWS credentials (AWS_ACCESS_KEY, AWS_SECRET_ACCESS, AWS_REGION)

## Meta WhatsApp Cloud API

1. Set `notification.whatsapp.provider=meta`
2. Set `WHATSAPP_API_TOKEN` and `WHATSAPP_PHONE_NUMBER_ID`
3. Set `NOTIFICATION_WHATSAPP_LOGIN_TO` (E.164 number like `+9198xxxxxx`)
4. Login now triggers a simple WhatsApp text to `NOTIFICATION_WHATSAPP_LOGIN_TO`

## Adding More Providers

1. Create implementation (e.g. `SendGridEmailProvider`) implementing `EmailProvider`
2. Add `@ConditionalOnProperty(name = "notification.email.provider", havingValue = "sendgrid")`
3. Add required API key to `.env` and reference in properties
