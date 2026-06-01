package com.thisjowi.auth.service;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.springframework.util.StreamUtils;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;

@Service
public class EmailService {

    private static final Logger log = LoggerFactory.getLogger(EmailService.class);

    @Value("${mail.sender.email}")
    private String senderEmail;

    @Value("${mail.sender.name}")
    private String senderName;

    private final JavaMailSender javaMailSender;

    public EmailService(JavaMailSender javaMailSender) {
        this.javaMailSender = javaMailSender;
    }

    public void sendVerificationEmail(String to, String verificationToken) {
        log.info("Preparing to send verification email to: {}", to);
        String subject = "Verify your email";
        String htmlContent = getVerificationEmailTemplate(verificationToken);
        sendEmail(to, subject, htmlContent);
    }

    public void sendPasswordResetEmail(String to, String name, String otp) {
        log.info("Preparing to send password reset email to: {}", to);
        String subject = "Reset your password";
        String htmlContent = getPasswordResetEmailTemplate(otp, name);
        sendEmail(to, subject, htmlContent);
    }

    private void sendEmail(String to, String subject, String htmlContent) {
        try {
            MimeMessage message = javaMailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");

            helper.setFrom(senderEmail, senderName);
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(htmlContent, true);

            javaMailSender.send(message);
            log.info("Email sent successfully to {}", to);
        } catch (MessagingException | UnsupportedEncodingException e) {
            log.error("Error occurred while sending email to {}: {}", to, e.getMessage());
            throw new RuntimeException("Failed to send email", e);
        }
    }

    private String getPasswordResetEmailTemplate(String otp, String name) {
        try {
            ClassPathResource resource = new ClassPathResource("templates/password-reset-email.html");
            String template = StreamUtils.copyToString(resource.getInputStream(), StandardCharsets.UTF_8);
            return template.replace("{{otp}}", otp).replace("{{name}}", name);
        } catch (IOException e) {
            log.error("Error loading email template", e);
            // Fallback to simple HTML if template loading fails
            return "<p>Hello " + name + ",</p><p>Use this OTP to reset your password: <strong>" + otp + "</strong></p>";
        }
    }

    private String getVerificationEmailTemplate(String token) {
        try {
            ClassPathResource resource = new ClassPathResource("templates/verification-email.html");
            String template = StreamUtils.copyToString(resource.getInputStream(), StandardCharsets.UTF_8);
            return template.replace("{{verificationToken}}", token);
        } catch (IOException e) {
            log.error("Error loading email template", e);
            // Fallback to simple HTML if template loading fails
            return "<p>Please verify your email using this token: <strong>" + token + "</strong></p>";
        }
    }
}
