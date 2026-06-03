package com.thisjowi.otp.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import io.swagger.v3.oas.annotations.tags.Tag;
import com.thisjowi.otp.entity.otp;
import com.thisjowi.otp.service.OtpService;
import com.thisjowi.otp.service.QrService;
import com.thisjowi.otp.util.JwtUtil;
import com.thisjowi.otp.util.EncryptionUtil;
import com.thisjowi.otp.kafka.SyncEventPublisher;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/v1/otp")
@Tag(name = "OTP / Authenticator", description = "TOTP code generation, QR decoding, OTP validation and encrypted secret management")
public class OtpController {

    private static final Logger log = LoggerFactory.getLogger(OtpController.class);

    private final OtpService otpService;
    private final QrService qrService;
    private final JwtUtil jwtUtil;
    private final EncryptionUtil encryptionUtil;

    @Autowired
    private SyncEventPublisher syncEventPublisher;

    public OtpController(OtpService otpService, QrService qrService, JwtUtil jwtUtil, EncryptionUtil encryptionUtil) {
        this.otpService = otpService;
        this.qrService = qrService;
        this.jwtUtil = jwtUtil;
        this.encryptionUtil = encryptionUtil;
    }

    @PostMapping("/decode-qr")
    public ResponseEntity<String> decodeQr(@RequestBody String base64Image) {
        try {
            if (base64Image == null || base64Image.length() > 10_000_000) {
                return ResponseEntity.status(400).body("Invalid QR image data");
            }
            String qrText = qrService.decodeQrFromBase64(base64Image);
            return ResponseEntity.ok(qrText);
        } catch (Exception e) {
            log.error("Error decoding QR", e);
            return ResponseEntity.status(400).body("Error decoding QR");
        }
    }

    @GetMapping
    public ResponseEntity<List<otp>> getAllOtps(
            @RequestHeader(value = "Authorization", required = false) String token) {
        Long userId = jwtUtil.extractUserId(token);
        if (userId == null) {
            return ResponseEntity.status(401).build();
        }
        return ResponseEntity.ok(otpService.getAllOtps(userId));
    }

    @GetMapping("/{id}")
    public ResponseEntity<otp> getOtp(
            @RequestHeader(value = "Authorization", required = false) String token,
            @PathVariable Long id) {
        Long userId = jwtUtil.extractUserId(token);
        if (userId == null) {
            return ResponseEntity.status(401).build();
        }
        Optional<otp> o = otpService.getOtp(id);
        if (o.isPresent() && !o.get().getUserId().equals(userId)) {
            return ResponseEntity.status(403).build();
        }
        return o.map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping
    public ResponseEntity<otp> createOtp(
            @RequestHeader(value = "Authorization", required = false) String token,
            @RequestBody CreateOtpRequest request) {

        Long userId = jwtUtil.extractUserId(token);
        if (userId == null) {
            return ResponseEntity.status(401).build();
        }

        String decryptedSecret = null;
        if (request.secret != null && !request.secret.isEmpty()) {
            try {
                decryptedSecret = encryptionUtil.decrypt(request.secret);
            } catch (Exception e) {
                log.warn("Failed to decrypt secret for user {}, using as-is", userId);
                decryptedSecret = request.secret;
            }
        }

        otp created = otpService.createOtp(userId, request.name, request.type, decryptedSecret,
                request.issuer, request.digits, request.period, request.algorithm);

        syncEventPublisher.publish(String.valueOf(userId), "created", Map.of(
            "id", String.valueOf(created.getId()),
            "issuer", created.getIssuer() != null ? created.getIssuer() : "",
            "label", created.getEmail() != null ? created.getEmail() : ""
        ));

        return ResponseEntity.ok(created);
    }

    public static class CreateOtpRequest {
        public String name;
        public String issuer;
        public String secret;
        public Integer digits;
        public Integer period;
        public String algorithm;
        public String type;
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> updateOtp(
            @RequestHeader(value = "Authorization", required = false) String token,
            @PathVariable Long id, @RequestBody otp updatedOtp) {

        Long userId = jwtUtil.extractUserId(token);
        if (userId == null) {
            return ResponseEntity.status(401).build();
        }

        Optional<otp> existing = otpService.getOtp(id);
        if (existing.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        if (!existing.get().getUserId().equals(userId)) {
            return ResponseEntity.status(403).build();
        }

        if (updatedOtp.getSecret() != null && !updatedOtp.getSecret().isEmpty()) {
            try {
                updatedOtp.setSecret(encryptionUtil.decrypt(updatedOtp.getSecret()));
            } catch (Exception e) {
                log.warn("Failed to decrypt secret on update for otp {}", id);
            }
        }

        otp updated = otpService.updateOtp(id, updatedOtp);

        syncEventPublisher.publish(String.valueOf(userId), "updated", Map.of(
            "id", String.valueOf(updated.getId()),
            "issuer", updated.getIssuer() != null ? updated.getIssuer() : "",
            "label", updated.getEmail() != null ? updated.getEmail() : ""
        ));

        return ResponseEntity.ok(updated);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteOtp(
            @RequestHeader(value = "Authorization", required = false) String token,
            @PathVariable Long id) {

        Long userId = jwtUtil.extractUserId(token);
        if (userId == null) {
            return ResponseEntity.status(401).build();
        }

        Optional<otp> existing = otpService.getOtp(id);
        if (existing.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        if (!existing.get().getUserId().equals(userId)) {
            return ResponseEntity.status(403).build();
        }

        otpService.deleteOtp(id);

        syncEventPublisher.publish(String.valueOf(userId), "deleted", Map.of(
            "id", String.valueOf(id)
        ));

        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/validate")
    public ResponseEntity<String> validateOtp(@PathVariable Long id, @RequestParam String code) {
        boolean valid = otpService.validateOtp(id, code);
        return valid ? ResponseEntity.ok("OTP válido") : ResponseEntity.status(400).body("OTP inválido o expirado");
    }
}
