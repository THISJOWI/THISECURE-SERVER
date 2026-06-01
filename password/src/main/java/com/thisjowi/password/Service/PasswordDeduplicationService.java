package com.thisjowi.password.Service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.thisjowi.password.Entity.Password;
import com.thisjowi.password.Repository.PasswordDao;
import com.thisjowi.password.Utils.Encryption;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

@Service
public class PasswordDeduplicationService {
    private static final Logger log = LoggerFactory.getLogger(PasswordDeduplicationService.class);

    private final PasswordDao passwordDao;
    private final Encryption encryption;

    public PasswordDeduplicationService(PasswordDao passwordDao, Encryption encryption) {
        this.passwordDao = passwordDao;
        this.encryption = encryption;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> analyzeDuplicates(Long userId) {
        log.info("Analyzing duplicates for user {}", userId);

        List<Password> userPasswords = passwordDao.findByUserId(userId);
        if (userPasswords == null || userPasswords.isEmpty()) {
            return Map.of("duplicates_found", 0, "message", "No passwords found for this user");
        }

        Map<String, List<Password>> groups = new HashMap<>();

        for (Password p : userPasswords) {
            String key = createDuplicateKey(p);
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(p);
        }

        List<Map<String, Object>> duplicateGroups = new ArrayList<>();
        int totalDuplicates = 0;

        for (var entry : groups.entrySet()) {
            if (entry.getValue().size() > 1) {
                List<Long> ids = new ArrayList<>();
                for (Password p : entry.getValue()) {
                    ids.add(p.getId());
                }
                duplicateGroups.add(Map.of(
                    "key", entry.getKey(),
                    "count", entry.getValue().size(),
                    "ids", ids
                ));
                totalDuplicates += entry.getValue().size() - 1;
            }
        }

        log.info("Found {} duplicate groups with {} total duplicates", duplicateGroups.size(), totalDuplicates);

        return Map.of(
            "duplicates_found", totalDuplicates,
            "duplicate_groups", duplicateGroups,
            "message", "Analysis complete"
        );
    }

    @Transactional
    public Map<String, Object> removeDuplicates(Long userId) {
        log.warn("Starting duplicate removal for user {}", userId);

        List<Password> userPasswords = passwordDao.findByUserId(userId);
        if (userPasswords == null || userPasswords.isEmpty()) {
            return Map.of("deleted_count", 0, "message", "No passwords to clean");
        }

        Map<String, List<Password>> groups = new HashMap<>();

        for (Password p : userPasswords) {
            String key = createDuplicateKey(p);
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(p);
        }

        int deletedCount = 0;

        for (var entry : groups.entrySet()) {
            List<Password> duplicates = entry.getValue();
            if (duplicates.size() > 1) {
                log.info("Found {} duplicates for key: {}", duplicates.size(), entry.getKey());

                duplicates.sort(Comparator.comparingLong(Password::getId).reversed());

                for (int i = 1; i < duplicates.size(); i++) {
                    Password duplicate = duplicates.get(i);
                    log.info("Deleting duplicate password id: {} (keeping id: {})",
                            duplicate.getId(), duplicates.get(0).getId());
                    passwordDao.deleteById(duplicate.getId());
                    deletedCount++;
                }
            }
        }

        log.info("Deleted {} duplicate passwords for user {}", deletedCount, userId);

        return Map.of(
            "deleted_count", deletedCount,
            "message", "Duplicate removal complete"
        );
    }

    private String createDuplicateKey(Password p) {
        return String.format("%s|%s|%s",
            p.getName() != null ? p.getName() : "",
            p.getWebsite() != null ? p.getWebsite() : "",
            ""
        );
    }
}
