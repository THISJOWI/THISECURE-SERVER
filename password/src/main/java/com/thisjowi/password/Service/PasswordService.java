package com.thisjowi.password.Service;

import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.thisjowi.password.Entity.Password;
import com.thisjowi.password.Repository.PasswordDao;
import com.thisjowi.password.Utils.Encryption;
import com.thisjowi.password.Utils.JwtUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.List;

@Service
@AllArgsConstructor
public class PasswordService {
    private static final Logger log = LoggerFactory.getLogger(PasswordService.class);

    private final PasswordDao passwordDao;
    private final JwtUtil jwtUtil;
    private final Encryption encryption;

    @Transactional
    public Password savePassword(Password password) {
        if (password.getPassword() != null && !password.getPassword().isEmpty()) {
            password.setPassword(encryption.encrypt(password.getPassword()));
        }
        if (password.getWebsite() != null && !password.getWebsite().isEmpty()) {
            password.setWebsite(encryption.encrypt(password.getWebsite()));
        }
        if (password.getName() != null && !password.getName().isEmpty()) {
            password.setName(encryption.encrypt(password.getName()));
        }
        if (password.getUsername() != null && !password.getUsername().isEmpty()) {
            password.setUsername(encryption.encrypt(password.getUsername()));
        }

        Password saved = passwordDao.insert(password);

        decryptPasswordFields(saved);
        return saved;
    }

    @Transactional
    public Password savePasswordForTokenWithDeduplication(String authHeader, Password passwordData) {
        String userId = extractUserIdFromToken(authHeader);
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("Invalid or expired token");
        }

        String nameToCheck = passwordData.getName() != null ? passwordData.getName().trim() : "";
        String websiteToCheck = passwordData.getWebsite() != null ? passwordData.getWebsite().trim() : "";

        String encryptedName = nameToCheck.isEmpty() ? "" : encryption.encrypt(nameToCheck);
        String encryptedWebsite = websiteToCheck.isEmpty() ? "" : encryption.encrypt(websiteToCheck);

        var existingOptional = passwordDao.findByUserIdAndNameAndWebsite(
            userId,
            encryptedName,
            encryptedWebsite
        );

        if (existingOptional.isPresent()) {
            Password existing = existingOptional.get();
            log.info("Duplicate detected for user {}, updating existing password id: {}", userId, existing.getId());

            if (passwordData.getPassword() != null && !passwordData.getPassword().isEmpty()) {
                existing.setPassword(encryption.encrypt(passwordData.getPassword()));
            }

            return updatePassword(existing);
        } else {
            log.info("No duplicate found, creating new password for user {}", userId);
            passwordData.setUserId(userId);
            return savePassword(passwordData);
        }
    }

    public List<Password> getPasswordsByToken(String authHeader) {
        String userId = extractUserIdFromToken(authHeader);
        if (userId == null || userId.isBlank()) {
            log.warn("Failed to extract userId from Authorization header");
            return Collections.emptyList();
        }

        log.info("User {} requested passwords", userId);
        return getPasswordsByUserId(userId);
    }

    @Transactional
    public Password savePasswordForToken(String authHeader, Password password) {
        String userId = extractUserIdFromToken(authHeader);
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("Invalid or expired token");
        }
        password.setUserId(userId);
        return savePassword(password);
    }

    @Transactional
    public Password updatePasswordByToken(String authHeader, Long id, Password passwordData) {
        String userId = extractUserIdFromToken(authHeader);
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("Invalid or expired token");
        }
        var opt = passwordDao.findById(id);
        if (opt.isEmpty()) {
            throw new IllegalArgumentException("Password not found");
        }
        Password existing = opt.get();
        if (!userId.equals(existing.getUserId())) {
            throw new SecurityException("Not authorized to update this resource");
        }

        if (passwordData.getName() != null && !passwordData.getName().trim().isEmpty()) {
            existing.setName(passwordData.getName().trim());
        }

        if (passwordData.getPassword() != null && !passwordData.getPassword().trim().isEmpty()) {
            existing.setPassword(passwordData.getPassword().trim());
        }

        if (passwordData.getWebsite() != null && !passwordData.getWebsite().trim().isEmpty()) {
            existing.setWebsite(passwordData.getWebsite().trim());
        }

        if (passwordData.getUsername() != null && !passwordData.getUsername().trim().isEmpty()) {
            existing.setUsername(passwordData.getUsername().trim());
        }

        return updatePassword(existing);
    }

    @Transactional
    public void deletePasswordByToken(String authHeader, Long id) {
        String userId = extractUserIdFromToken(authHeader);
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("Invalid or expired token");
        }
        var opt = passwordDao.findById(id);
        if (opt.isEmpty()) {
            throw new IllegalArgumentException("Password not found");
        }
        Password p = opt.get();
        if (!userId.equals(p.getUserId())) {
            throw new SecurityException("Not authorized to delete this resource");
        }
        passwordDao.deleteById(id);
    }

    private String extractUserIdFromToken(String authHeader) {
        log.debug("Extracting userId from Authorization header");

        if (authHeader == null || authHeader.isBlank()) {
            log.debug("No Authorization header provided");
            return null;
        }

        String token = authHeader.startsWith("Bearer ") ? authHeader.substring(7) : authHeader;
        String userId = jwtUtil.extractUserId(token);

        if (userId != null && !userId.isBlank()) {
            log.info("UserId extracted successfully");
            return userId;
        }

        log.warn("Failed to extract userId from Authorization header");
        return null;
    }

    private List<Password> getPasswordsByUserId(String userId) {
        if (userId == null || userId.isBlank()) {
            log.warn("Invalid userId: {}", userId);
            return Collections.emptyList();
        }

        List<Password> passwords = passwordDao.findByUserId(userId);

        if (passwords == null || passwords.isEmpty()) {
            return Collections.emptyList();
        }

        passwords.forEach(this::decryptPasswordFields);

        return passwords;
    }

    private void decryptPasswordFields(Password p) {
        if (p.getPassword() != null) {
            try {
                String decrypted = encryption.decrypt(p.getPassword());
                if (decrypted != null) {
                    p.setPassword(decrypted);
                } else {
                    log.warn("Decryption returned null for password field of id {}, keeping encrypted", p.getId());
                }
            } catch (Exception e) {
                log.error("Failed to decrypt password field for id {}: {}", p.getId(), e.getMessage());
            }
        }

        if (p.getWebsite() != null) {
            try {
                String decrypted = encryption.decrypt(p.getWebsite());
                if (decrypted != null) {
                    p.setWebsite(decrypted);
                } else {
                    log.warn("Decryption returned null for website field of id {}, keeping encrypted", p.getId());
                }
            } catch (Exception e) {
                log.error("Failed to decrypt website field for id {}: {}", p.getId(), e.getMessage());
            }
        }

        if (p.getName() != null) {
            try {
                String decrypted = encryption.decrypt(p.getName());
                if (decrypted != null) {
                    p.setName(decrypted);
                } else {
                    log.warn("Decryption returned null for name/title field of id {}, keeping encrypted", p.getId());
                }
            } catch (Exception e) {
                log.error("Failed to decrypt name/title field for id {}: {}", p.getId(), e.getMessage());
            }
        }

        if (p.getUsername() != null) {
            try {
                String decrypted = encryption.decrypt(p.getUsername());
                if (decrypted != null) {
                    p.setUsername(decrypted);
                } else {
                    log.warn("Decryption returned null for username field of id {}, keeping encrypted", p.getId());
                }
            } catch (Exception e) {
                log.error("Failed to decrypt username field for id {}: {}", p.getId(), e.getMessage());
            }
        }
    }

    @Transactional
    public Password updatePassword(Password password) {
        if (password.getPassword() != null && !password.getPassword().isEmpty()) {
            password.setPassword(encryption.encrypt(password.getPassword()));
        }
        if (password.getWebsite() != null && !password.getWebsite().isEmpty()) {
            password.setWebsite(encryption.encrypt(password.getWebsite()));
        }
        if (password.getName() != null && !password.getName().isEmpty()) {
            password.setName(encryption.encrypt(password.getName()));
        }
        if (password.getUsername() != null && !password.getUsername().isEmpty()) {
            password.setUsername(encryption.encrypt(password.getUsername()));
        }

        passwordDao.update(password);

        decryptPasswordFields(password);
        return password;
    }
}
