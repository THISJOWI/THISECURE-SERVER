package com.thisjowi.otp.repository;

import com.thisjowi.otp.entity.otp;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;

@Repository
public class JdbcOtpDao implements OtpDao {

    private final JdbcTemplate jdbcTemplate;

    private final RowMapper<otp> rowMapper = (rs, rowNum) -> {
        otp o = new otp();
        o.setId(rs.getLong("id"));
        o.setUserId(rs.getString("user_id"));
        o.setEmail(rs.getString("email"));
        o.setSecret(rs.getString("secret"));
        String expiresAtStr = rs.getString("expires_at");
        o.setExpiresAt(expiresAtStr != null ? Long.parseLong(expiresAtStr) : null);
        o.setType(rs.getString("type"));
        o.setIssuer(rs.getString("issuer"));
        String digitsStr = rs.getString("digits");
        o.setDigits(digitsStr != null ? Integer.parseInt(digitsStr) : null);
        o.setPeriod(rs.getInt("period"));
        if (rs.wasNull()) o.setPeriod(null);
        o.setAlgorithm(rs.getString("algorithm"));
        o.setValid(Boolean.parseBoolean(rs.getString("valid")));
        return o;
    };

    public JdbcOtpDao(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public List<otp> findByUserId(String userId) {
        return jdbcTemplate.query("SELECT * FROM otp WHERE user_id = ?", rowMapper, userId);
    }

    @Override
    public Optional<otp> findById(Long id) {
        return jdbcTemplate.query("SELECT * FROM otp WHERE id = ?", rowMapper, id)
                .stream().findFirst();
    }

    @Override
    public otp insert(otp otp) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO otp (user_id, email, secret, expires_at, type, issuer, digits, period, algorithm, valid) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, otp.getUserId());
            ps.setString(2, otp.getEmail());
            ps.setString(3, otp.getSecret());
            ps.setString(4, String.valueOf(otp.getExpiresAt()));
            ps.setString(5, otp.getType());
            ps.setString(6, otp.getIssuer());
            ps.setString(7, otp.getDigits() != null ? String.valueOf(otp.getDigits()) : null);
            ps.setObject(8, otp.getPeriod());
            ps.setString(9, otp.getAlgorithm());
            ps.setString(10, String.valueOf(otp.getValid()));
            return ps;
        }, keyHolder);

        Number key = keyHolder.getKey();
        if (key != null) {
            otp.setId(key.longValue());
        }
        return otp;
    }

    @Override
    public void update(otp otp) {
        jdbcTemplate.update(
                "UPDATE otp SET user_id = ?, email = ?, secret = ?, expires_at = ?, type = ?, issuer = ?, digits = ?, period = ?, algorithm = ?, valid = ? WHERE id = ?",
                otp.getUserId(), otp.getEmail(), otp.getSecret(), String.valueOf(otp.getExpiresAt()),
                otp.getType(), otp.getIssuer(), otp.getDigits() != null ? String.valueOf(otp.getDigits()) : null,
                otp.getPeriod(), otp.getAlgorithm(), String.valueOf(otp.getValid()), otp.getId());
    }

    @Override
    public void removeOtp(Long id) {
        jdbcTemplate.update("DELETE FROM otp WHERE id = ?", id);
    }
}
