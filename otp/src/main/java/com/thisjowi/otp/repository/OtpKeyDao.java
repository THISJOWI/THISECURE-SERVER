package com.thisjowi.otp.repository;

import com.thisjowi.otp.model.OtpKey;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

@Repository
public class OtpKeyDao {

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public OtpKeyDao(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private static final String ALL_COLUMNS = "id, user_id, otp, created_at";

    private static final RowMapper<OtpKey> ROW_MAPPER = (rs, rowNum) -> {
        OtpKey key = new OtpKey();
        key.setId(rs.getLong("id"));
        key.setUserId(rs.getString("user_id"));
        key.setOtp(rs.getString("otp"));
        Timestamp ts = rs.getTimestamp("created_at");
        if (ts != null) {
            key.setCreatedAt(ts.toLocalDateTime());
        }
        return key;
    };

    public OtpKey insert(OtpKey otpKey) {
        String sql = "INSERT INTO otp_key (user_id, otp, created_at) VALUES (:userId, :otp, :createdAt)";
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("userId", otpKey.getUserId())
                .addValue("otp", otpKey.getOtp())
                .addValue("createdAt", otpKey.getCreatedAt() != null ? Timestamp.valueOf(otpKey.getCreatedAt()) : null);
        GeneratedKeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(sql, params, keyHolder, new String[]{"id"});
        otpKey.setId(keyHolder.getKey().longValue());
        return otpKey;
    }

    public Optional<OtpKey> findByUserId(String userId) {
        String sql = "SELECT " + ALL_COLUMNS + " FROM otp_key WHERE user_id = :userId";
        List<OtpKey> results = jdbcTemplate.query(sql, new MapSqlParameterSource("userId", userId), ROW_MAPPER);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }
}
