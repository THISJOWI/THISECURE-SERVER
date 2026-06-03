package com.thisjowi.otp.repository;

import com.thisjowi.otp.entity.otp;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface OtpDao extends CrudRepository<otp, Long> {

    List<otp> findByUserId(Long userId);

    @Query("SELECT * FROM otp WHERE id = :id")
    Optional<otp> findById(@Param("id") Long id);

    @Query("DELETE FROM otp WHERE id = :id")
    void deleteById(@Param("id") Long id);
}
