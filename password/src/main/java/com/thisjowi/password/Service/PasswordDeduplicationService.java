package com.thisjowi.password.Service;

import com.thisjowi.password.Entity.Password;
import com.thisjowi.password.Repository.PasswordDao;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class PasswordDeduplicationService {

    private final PasswordDao passwordDao;

    public PasswordDeduplicationService(PasswordDao passwordDao) {
        this.passwordDao = passwordDao;
    }

    public Map<String, Object> analyzeDuplicates(Long userId) {
        List<Password> allPasswords = passwordDao.findByUserId(userId);

        Map<String, List<Password>> groups = allPasswords.stream()
                .collect(Collectors.groupingBy(
                        p -> {
                            String name = p.getName() != null ? p.getName() : "";
                            String website = p.getWebsite() != null ? p.getWebsite() : "";
                            return name + "::" + website;
                        }
                ));

        List<Map<String, Object>> duplicates = new ArrayList<>();
        for (Map.Entry<String, List<Password>> entry : groups.entrySet()) {
            if (entry.getValue().size() > 1) {
                Map<String, Object> dup = new HashMap<>();
                dup.put("name", entry.getValue().get(0).getName());
                dup.put("website", entry.getValue().get(0).getWebsite());
                dup.put("count", entry.getValue().size());
                dup.put("ids", entry.getValue().stream().map(Password::getId).collect(Collectors.toList()));
                duplicates.add(dup);
            }
        }

        Map<String, Object> result = new HashMap<>();
        result.put("userId", userId);
        result.put("totalPasswords", allPasswords.size());
        result.put("duplicateGroups", duplicates.size());
        result.put("duplicates", duplicates);

        return result;
    }

    @Transactional
    public Map<String, Object> removeDuplicates(Long userId) {
        List<Password> allPasswords = passwordDao.findByUserId(userId);

        Map<String, List<Password>> groups = allPasswords.stream()
                .collect(Collectors.groupingBy(
                        p -> {
                            String name = p.getName() != null ? p.getName() : "";
                            String website = p.getWebsite() != null ? p.getWebsite() : "";
                            return name + "::" + website;
                        }
                ));

        int removed = 0;
        List<Long> removedIds = new ArrayList<>();

        for (Map.Entry<String, List<Password>> entry : groups.entrySet()) {
            List<Password> group = entry.getValue();
            if (group.size() > 1) {
                group.sort(Comparator.comparingLong(Password::getId).reversed());
                Password keep = group.get(0);
                for (int i = 1; i < group.size(); i++) {
                    passwordDao.deleteById(group.get(i).getId());
                    removedIds.add(group.get(i).getId());
                    removed++;
                }
            }
        }

        Map<String, Object> result = new HashMap<>();
        result.put("userId", userId);
        result.put("removed", removed);
        result.put("removedIds", removedIds);

        return result;
    }
}
