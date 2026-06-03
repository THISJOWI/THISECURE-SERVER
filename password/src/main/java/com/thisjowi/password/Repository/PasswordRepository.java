package com.thisjowi.password.Repository;

import com.thisjowi.password.Entity.Password;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PasswordRepository extends JpaRepository<Password, Long> {
    List<Password> findByName(String name);
    List<Password> findByUserId(Long userId);
    Optional<Password> findByUserIdAndNameAndWebsite(Long userId, String name, String website);
}
