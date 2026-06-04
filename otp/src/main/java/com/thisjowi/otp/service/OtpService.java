package com.thisjowi.otp.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.thisjowi.otp.dto.OtpCreatedEvent;
import com.thisjowi.otp.entity.otp;
import com.thisjowi.otp.kafka.KafkaProducerService;
import com.thisjowi.otp.repository.OtpDao;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

@Service
public class OtpService {

    private static final Logger logger = LoggerFactory.getLogger(OtpService.class);

    private final OtpDao otpDao;
    private final KafkaProducerService kafkaProducerService;

    public OtpService(OtpDao otpDao, KafkaProducerService kafkaProducerService) {
        this.otpDao = otpDao;
        this.kafkaProducerService = kafkaProducerService;
    }

    @Transactional(readOnly = true)
    public List<otp> getAllOtps(String userId) {
        if (userId != null && !userId.isEmpty()) {
            return otpDao.findByUserId(userId);
        }
        return List.of();
    }

    @Transactional(readOnly = true)
    public Optional<otp> getOtp(Long id) {
        return otpDao.findById(id);
    }

    @Transactional
    public otp createOtp(String userId, String name, String type, String secret, String issuer, Integer digits, Integer period, String algorithm) {
        if (secret != null && !secret.isEmpty()) {
            String normalizedSecret = secret.trim().replace(" ", "").toUpperCase();
            List<otp> existing = otpDao.findByUserId(userId);
            for (otp entry : existing) {
                String existingSecret = entry.getSecret();
                if (existingSecret != null) {
                    String normalizedExisting = existingSecret.trim().replace(" ", "").toUpperCase();
                    if (normalizedExisting.equals(normalizedSecret)) {
                        logger.info("Duplicate OTP creation attempt prevented for user {} with secret ending in ...{}", userId, normalizedSecret.substring(Math.max(0, normalizedSecret.length() - 4)));
                        return entry;
                    }
                }
            }
        }

        otp o = new otp();
        o.setUserId(userId);
        o.setEmail(name);
        o.setType(type);
        o.setSecret((secret != null && !secret.isEmpty()) ? secret : generateSecret());
        o.setIssuer(issuer);
        o.setDigits(digits);
        o.setPeriod(period);
        o.setAlgorithm(algorithm);
        o.setValid(true);
        o.setExpiresAt(System.currentTimeMillis() + (period != null ? period * 1000L : 30000L));

        otp saved = otpDao.insert(o);

        OtpCreatedEvent event = new OtpCreatedEvent(
            saved.getId(),
            userId,
            name,
            type,
            "OTP_CREATED",
            System.currentTimeMillis(),
            saved.getExpiresAt()
        );
        kafkaProducerService.sendOtpCreatedEvent(event);

        return saved;
    }

    @Transactional
    public otp createOtpForUser(String userId, String email, String type, long validitySeconds) {
        otp o = new otp();
        o.setUserId(userId);
        o.setEmail(email);
        o.setType(type);
        o.setValid(true);
        o.setExpiresAt(System.currentTimeMillis() + (validitySeconds * 1000));
        o.setSecret(generateSecret());
        otp saved = otpDao.insert(o);

        try {
            OtpCreatedEvent event = new OtpCreatedEvent(
                saved.getId(),
                userId,
                email,
                type,
                "OTP_CREATED",
                System.currentTimeMillis(),
                saved.getExpiresAt()
            );
            kafkaProducerService.sendOtpCreatedEvent(event);
            logger.info("OTP created for user: {} with userId: {}", email, userId);
        } catch (Exception e) {
            logger.error("Error sending OTP created event to Kafka", e);
        }

        return saved;
    }

    @Transactional
    public otp updateOtp(Long id, otp updatedOtp) {
        updatedOtp.setId(id);
        otpDao.update(updatedOtp);
        return updatedOtp;
    }

    @Transactional
    public void deleteOtp(Long id) {
        otpDao.deleteById(id);
    }

    @Transactional(readOnly = true)
    public boolean validateOtp(Long id, String code) {
        Optional<otp> o = otpDao.findById(id);
        if (o.isPresent() && o.get().getValid() && o.get().getExpiresAt() > System.currentTimeMillis()) {
            return o.get().getSecret().equals(code);
        }
        return false;
    }

    private String generateSecret() {
        SecureRandom random = new SecureRandom();
        byte[] bytes = new byte[20];
        random.nextBytes(bytes);
        return Base64.getEncoder().encodeToString(bytes);
    }
}
