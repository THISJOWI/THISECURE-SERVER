package com.thisjowi.auth.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import lombok.extern.slf4j.Slf4j;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Service for encrypting/decrypting sensitive LDAP data like bind passwords
 * Uses AES-256 encryption
 * 
 * Security Recommendations:
 * 1. Store the encryption key in environment variables or a secure vault (AWS Secrets Manager, Azure Key Vault)
 * 2. Use a strong random key (32 bytes for AES-256)
 * 3. Rotate keys periodically
 * 4. Never commit the key to version control
 */
@Service
@Slf4j
public class LdapEncryptionService {

    private static final String ALGORITHM = "AES";
    private static final String CIPHER_ALGORITHM = "AES";
    private static final int KEY_SIZE = 256;

    @Value("${ldap.encryption.key:${LDAP_ENCRYPTION_KEY:}}")
    private String encryptionKeyString;

    private SecretKey secretKey;

    /**
     * Initialize the encryption key from configuration
     * Must be called after property injection
     */
    public void initializeKey() {
        try {
            if (encryptionKeyString == null || encryptionKeyString.isEmpty()) {
                log.warn("LDAP encryption key not provided. Using default key generation.");
                generateDefaultKey();
            } else {
                // Decode the base64 encoded key from configuration
                byte[] decodedKey = Base64.getDecoder().decode(encryptionKeyString);
                
                if (decodedKey.length != 32) {
                    throw new IllegalArgumentException(
                            "Invalid encryption key length. Expected 32 bytes (256 bits), got " + decodedKey.length);
                }
                
                secretKey = new SecretKeySpec(decodedKey, 0, decodedKey.length, ALGORITHM);
                log.info("LDAP encryption key initialized successfully");
            }
        } catch (IllegalArgumentException e) {
            log.error("Error initializing encryption key: {}", e.getMessage());
            throw new RuntimeException("Failed to initialize encryption key", e);
        }
    }

    /**
     * Generate a default encryption key (for development only)
     * In production, always use an external key
     */
    private void generateDefaultKey() {
        try {
            KeyGenerator keyGenerator = KeyGenerator.getInstance(ALGORITHM);
            keyGenerator.init(KEY_SIZE);
            secretKey = keyGenerator.generateKey();
            log.warn("Generated default encryption key. This should only be used in development!");
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate encryption key", e);
        }
    }

    /**
     * Encrypt a plaintext password
     */
    public String encrypt(String plaintext) {
        if (plaintext == null || plaintext.isEmpty()) {
            return "";
        }

        try {
            if (secretKey == null) {
                initializeKey();
            }

            Cipher cipher = Cipher.getInstance(CIPHER_ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey);
            byte[] encryptedData = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            
            // Return base64 encoded encrypted data
            return Base64.getEncoder().encodeToString(encryptedData);

        } catch (Exception e) {
            log.error("Error encrypting data: {}", e.getMessage());
            throw new RuntimeException("Encryption failed", e);
        }
    }

    /**
     * Decrypt an encrypted password
     */
    public String decrypt(String encryptedBase64) {
        if (encryptedBase64 == null || encryptedBase64.isEmpty()) {
            return "";
        }

        try {
            if (secretKey == null) {
                initializeKey();
            }

            Cipher cipher = Cipher.getInstance(CIPHER_ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, secretKey);
            
            byte[] encryptedData = Base64.getDecoder().decode(encryptedBase64);
            byte[] decryptedData = cipher.doFinal(encryptedData);
            
            return new String(decryptedData, StandardCharsets.UTF_8);

        } catch (IllegalArgumentException e) {
            log.error("Invalid base64 encoded data: {}", e.getMessage());
            throw new RuntimeException("Decryption failed: Invalid format", e);
        } catch (Exception e) {
            log.error("Error decrypting data: {}", e.getMessage());
            throw new RuntimeException("Decryption failed", e);
        }
    }

    /**
     * Generate a new encryption key and return it as base64 encoded string
     * Use this to generate a key for your configuration
     */
    public static String generateNewKey() {
        try {
            KeyGenerator keyGenerator = KeyGenerator.getInstance("AES");
            keyGenerator.init(256);
            SecretKey key = keyGenerator.generateKey();
            
            // Return base64 encoded key
            return Base64.getEncoder().encodeToString(key.getEncoded());

        } catch (Exception e) {
            throw new RuntimeException("Failed to generate encryption key", e);
        }
    }
}
