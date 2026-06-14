package com.thisjowi.note.Utils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

@Component
public class EncryptionUtil {

    private static final Logger logger = LoggerFactory.getLogger(EncryptionUtil.class);

    private static final String AES_ALGORITHM = "AES/GCM/NoPadding";
    private static final int AES_KEY_SIZE = 32;
    private static final int IV_SIZE = 12;
    private static final int TAG_SIZE = 128;

    private final byte[] secretKeyBytes;

    private static EncryptionUtil instance;

    public EncryptionUtil(@Value("${encryption.secret-key}") String secretKey) {
        if (secretKey == null || secretKey.trim().isEmpty()) {
            throw new IllegalArgumentException("encryption.secret-key must be provided");
        }
        if (secretKey.length() < AES_KEY_SIZE) {
            throw new IllegalArgumentException("encryption.secret-key must be at least " + AES_KEY_SIZE + " characters");
        }
        this.secretKeyBytes = deriveKeyBytes(secretKey);
        instance = this;
        logger.info("EncryptionUtil initialized with AES-256-GCM");
    }

    private static byte[] deriveKeyBytes(String secretKey) {
        byte[] keyBytes = new byte[AES_KEY_SIZE];
        byte[] secretBytes = secretKey.getBytes(StandardCharsets.UTF_8);
        System.arraycopy(secretBytes, 0, keyBytes, 0, Math.min(secretBytes.length, keyBytes.length));
        return keyBytes;
    }

    public static String encrypt(String plaintext) {
        if (instance == null) {
            throw new IllegalStateException("EncryptionUtil not initialized");
        }
        if (plaintext == null || plaintext.isEmpty()) {
            return plaintext;
        }

        try {
            byte[] iv = new byte[IV_SIZE];
            SecureRandom random = new SecureRandom();
            random.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(AES_ALGORITHM);
            SecretKeySpec keySpec = new SecretKeySpec(instance.secretKeyBytes, 0, AES_KEY_SIZE, "AES");
            GCMParameterSpec gcmSpec = new GCMParameterSpec(TAG_SIZE, iv);
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, gcmSpec);

            byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            byte[] combined = new byte[IV_SIZE + encrypted.length];
            System.arraycopy(iv, 0, combined, 0, IV_SIZE);
            System.arraycopy(encrypted, 0, combined, IV_SIZE, encrypted.length);

            return Base64.getEncoder().encodeToString(combined);
        } catch (Exception e) {
            logger.error("Encryption failed", e);
            throw new RuntimeException("Error encrypting data", e);
        }
    }

    public static String decrypt(String encryptedText) {
        if (instance == null) {
            throw new IllegalStateException("EncryptionUtil not initialized");
        }
        if (encryptedText == null || encryptedText.isBlank()) {
            return encryptedText;
        }

        try {
            byte[] combined;
            try {
                combined = Base64.getDecoder().decode(encryptedText);
            } catch (IllegalArgumentException e) {
                logger.warn("Invalid Base64 format, returning original string");
                return encryptedText;
            }

            if (combined.length < IV_SIZE) {
                logger.warn("Encrypted data too short, returning original string");
                return encryptedText;
            }

            byte[] iv = new byte[IV_SIZE];
            byte[] ciphertext = new byte[combined.length - IV_SIZE];
            System.arraycopy(combined, 0, iv, 0, IV_SIZE);
            System.arraycopy(combined, IV_SIZE, ciphertext, 0, ciphertext.length);

            Cipher cipher = Cipher.getInstance(AES_ALGORITHM);
            SecretKeySpec keySpec = new SecretKeySpec(instance.secretKeyBytes, 0, AES_KEY_SIZE, "AES");
            GCMParameterSpec gcmSpec = new GCMParameterSpec(TAG_SIZE, iv);
            cipher.init(Cipher.DECRYPT_MODE, keySpec, gcmSpec);

            byte[] decrypted = cipher.doFinal(ciphertext);
            return new String(decrypted, StandardCharsets.UTF_8);
        } catch (Exception e) {
            logger.error("Decryption failed", e);
            throw new RuntimeException("Error decrypting data", e);
        }
    }
}
