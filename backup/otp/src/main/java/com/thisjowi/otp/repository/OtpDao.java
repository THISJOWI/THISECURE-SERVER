package com.thisjowi.otp.repository;

import com.thisjowi.otp.entity.otp;

import java.util.List;
import java.util.Optional;

public interface OtpDao {

    List<otp> findByUserId(String userId);

    Optional<otp> findById(Long id);

    otp insert(otp otp);

    void update(otp otp);

    void removeOtp(Long id);
}
