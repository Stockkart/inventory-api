package com.inventory.notifications.template;

import com.inventory.notifications.domain.model.EmailTemplate;

import java.util.Map;

/**
 * Provides default HTML content for email templates.
 * Variables use {{name}} placeholders. Add Resend template ID mapping when using Resend-hosted templates.
 */
public final class EmailTemplateContent {

  private EmailTemplateContent() {}

  /**
   * Returns default subject and HTML body for a template. Variables: userName, userEmail, etc.
   */
  public static TemplateContent get(EmailTemplate template, Map<String, Object> variables) {
    return switch (template) {
      case WELCOME_SIGNUP -> new TemplateContent(
          "Welcome to Stock Kart",
          """
              <!DOCTYPE html>
              <html>
              <head><meta charset="UTF-8"></head>
              <body style="font-family: sans-serif; line-height: 1.6; color: #333;">
                <h2>Welcome to Stock Kart!</h2>
                <p>Hi {{userName}},</p>
                <p>Thank you for signing up. Your account has been created successfully.</p>
                <p>You can now log in and start managing your inventory.</p>
                <p>If you have any questions, feel free to reach out.</p>
                <p>Best regards,<br>The Stock Kart Team</p>
              </body>
              </html>
              """
      );
      case LOGIN_NOTIFICATION -> new TemplateContent(
          "New login to your Stock Kart account",
          """
              <!DOCTYPE html>
              <html>
              <head><meta charset="UTF-8"></head>
              <body style="font-family: sans-serif; line-height: 1.6; color: #333;">
                <h2>New login detected</h2>
                <p>Hi {{userName}},</p>
                <p>We noticed a new login to your Stock Kart account ({{userEmail}}).</p>
                <p>If this was you, you can ignore this email. If not, please change your password immediately.</p>
                <p>Best regards,<br>The Stock Kart Team</p>
              </body>
              </html>
              """
      );
      case FORGOT_PASSWORD -> new TemplateContent(
          "Reset your Stock Kart password",
          """
              <!DOCTYPE html>
              <html>
              <head><meta charset="UTF-8"></head>
              <body style="font-family: sans-serif; line-height: 1.6; color: #333;">
                <h2>Reset your password</h2>
                <p>Hi {{userName}},</p>
                <p>You requested a password reset for your Stock Kart account ({{userEmail}}).</p>
                <p>Click the link below to reset your password. This link will expire in 1 hour.</p>
                <p><a href="{{resetLink}}" style="display: inline-block; background: #3b82f6; color: white; padding: 12px 24px; text-decoration: none; border-radius: 8px; margin: 16px 0;">Reset Password</a></p>
                <p>Or copy and paste this URL into your browser:<br><a href="{{resetLink}}">{{resetLink}}</a></p>
                <p>If you did not request this, you can safely ignore this email.</p>
                <p>Best regards,<br>The Stock Kart Team</p>
              </body>
              </html>
              """
      );
    };
  }

  public record TemplateContent(String subject, String htmlBody) {}
}
