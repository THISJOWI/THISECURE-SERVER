package com.thisjowi.password.Repository;

import com.thisjowi.password.Entity.Password;

import java.util.List;
import java.util.Optional;

public interface PasswordDao {
    List<Password> findByUserId(String userId);
    Optional<Password> findById(Long id);
    Optional<Password> findByUserIdAndNameAndWebsite(String userId, String name, String website);
    Password insert(Password password);
    void update(Password password);
    void deleteById(Long id);
}
