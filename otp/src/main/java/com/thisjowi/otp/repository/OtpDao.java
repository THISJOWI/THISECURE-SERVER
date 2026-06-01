package com.thisjowi.otp.repository;

import com.thisjowi.otp.entity.otp;
import com.thisjowi.otp.util.EncryptionUtil;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class OtpDao {

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public OtpDao(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private static final String ALL_COLUMNS = "id, user_id, email, secret, expires_at, type, issuer, digits, period, algorithm, valid";

    private static final RowMapper<otp> ROW_MAPPER = (rs, rowNum) -> {
        otp o = new otp();
        o.setId(rs.getLong("id"));
        o.setUserId(rs.getObject("user_id", Long.class));
        o.setEmail(decryptField(rs.getString("email")));
        o.setSecret(decryptField(rs.getString("secret")));
        o.setExpiresAt(decryptLongField(rs.getString("expires_at")));
        o.setType(decryptField(rs.getString("type")));
        o.setIssuer(rs.getString("issuer"));
        o.setDigits(decryptIntegerField(rs.getString("digits")));
        o.setPeriod(rs.getObject("period", Integer.class));
        o.setAlgorithm(decryptField(rs.getString("algorithm")));
        o.setValid(decryptBooleanField(rs.getString("valid")));
        return o;
    };

    private static String decryptField(String value) {
        return value != null ? EncryptionUtil.decrypt(value) : null;
    }

    private static Long decryptLongField(String value) {
        if (value == null) return null;
        String decrypted = EncryptionUtil.decrypt(value);
        return decrypted != null ? Long.parseLong(decrypted) : null;
    }

    private static Integer decryptIntegerField(String value) {
        if (value == null) return null;
        String decrypted = EncryptionUtil.decrypt(value);
        return decrypted != null ? Integer.parseInt(decrypted) : null;
    }

    private static Boolean decryptBooleanField(String value) {
        if (value == null) return null;
        String decrypted = EncryptionUtil.decrypt(value);
        return decrypted != null ? Boolean.parseBoolean(decrypted) : null;
    }

    public otp insert(otp o) {
        String sql = "INSERT INTO otp (user_id, email, secret, expires_at, type, issuer, digits, period, algorithm, valid) " +
                     "VALUES (:userId, :email, :secret, :expiresAt, :type, :issuer, :digits, :period, :algorithm, :valid)";
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("userId", o.getUserId())
                .addValue("email", EncryptionUtil.encrypt(o.getEmail()))
                .addValue("secret", EncryptionUtil.encrypt(o.getSecret()))
                .addValue("expiresAt", o.getExpiresAt() != null ? EncryptionUtil.encrypt(String.valueOf(o.getExpiresAt())) : null)
                .addValue("type", EncryptionUtil.encrypt(o.getType()))
                .addValue("issuer", o.getIssuer())
                .addValue("digits", o.getDigits() != null ? EncryptionUtil.encrypt(String.valueOf(o.getDigits())) : null)
                .addValue("period", o.getPeriod())
                .addValue("algorithm", o.getAlgorithm() != null ? EncryptionUtil.encrypt(o.getAlgorithm()) : null)
                .addValue("valid", o.getValid() != null ? EncryptionUtil.encrypt(String.valueOf(o.getValid())) : null);
        GeneratedKeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(sql, params, keyHolder, new String[]{"id"});
        o.setId(keyHolder.getKey().longValue());
        return o;
    }

    public void update(otp o) {
        String sql = "UPDATE otp SET user_id = :userId, email = :email, secret = :secret, expires_at = :expiresAt, " +
                     "type = :type, issuer = :issuer, digits = :digits, period = :period, algorithm = :algorithm, " +
                     "valid = :valid WHERE id = :id";
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("userId", o.getUserId())
                .addValue("email", EncryptionUtil.encrypt(o.getEmail()))
                .addValue("secret", EncryptionUtil.encrypt(o.getSecret()))
                .addValue("expiresAt", o.getExpiresAt() != null ? EncryptionUtil.encrypt(String.valueOf(o.getExpiresAt())) : null)
                .addValue("type", EncryptionUtil.encrypt(o.getType()))
                .addValue("issuer", o.getIssuer())
                .addValue("digits", o.getDigits() != null ? EncryptionUtil.encrypt(String.valueOf(o.getDigits())) : null)
                .addValue("period", o.getPeriod())
                .addValue("algorithm", o.getAlgorithm() != null ? EncryptionUtil.encrypt(o.getAlgorithm()) : null)
                .addValue("valid", o.getValid() != null ? EncryptionUtil.encrypt(String.valueOf(o.getValid())) : null)
                .addValue("id", o.getId());
        jdbcTemplate.update(sql, params);
    }

    public Optional<otp> findById(Long id) {
        String sql = "SELECT " + ALL_COLUMNS + " FROM otp WHERE id = :id";
        List<otp> results = jdbcTemplate.query(sql, new MapSqlParameterSource("id", id), ROW_MAPPER);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    public List<otp> findByUserId(Long userId) {
        String sql = "SELECT " + ALL_COLUMNS + " FROM otp WHERE user_id = :userId";
        return jdbcTemplate.query(sql, new MapSqlParameterSource("userId", userId), ROW_MAPPER);
    }

    public void deleteById(Long id) {
        String sql = "DELETE FROM otp WHERE id = :id";
        jdbcTemplate.update(sql, new MapSqlParameterSource("id", id));
    }
}
