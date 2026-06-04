package com.thisjowi.password.Repository;

import com.thisjowi.password.Entity.Password;
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
public class JdbcPasswordDao implements PasswordDao {

    private final JdbcTemplate jdbcTemplate;

    private final RowMapper<Password> rowMapper = (rs, rowNum) -> {
        Password p = new Password();
        p.setId(rs.getLong("id"));
        p.setPassword(rs.getString("password"));
        p.setName(rs.getString("name"));
        p.setWebsite(rs.getString("website"));
        p.setUserId(rs.getString("user_id"));
        return p;
    };

    public JdbcPasswordDao(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public List<Password> findByUserId(String userId) {
        return jdbcTemplate.query("SELECT * FROM password WHERE user_id = ?", rowMapper, userId);
    }

    @Override
    public Optional<Password> findById(Long id) {
        return jdbcTemplate.query("SELECT * FROM password WHERE id = ?", rowMapper, id)
                .stream().findFirst();
    }

    @Override
    public Optional<Password> findByUserIdAndNameAndWebsite(String userId, String name, String website) {
        return jdbcTemplate.query(
                "SELECT * FROM password WHERE user_id = ? AND name = ? AND website = ?",
                rowMapper, userId, name, website).stream().findFirst();
    }

    @Override
    public Password insert(Password password) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO password (password, name, website, user_id) VALUES (?, ?, ?, ?)",
                    new String[]{"id"});
            ps.setString(1, password.getPassword());
            ps.setString(2, password.getName());
            ps.setString(3, password.getWebsite());
            ps.setString(4, password.getUserId());
            return ps;
        }, keyHolder);

        Number key = keyHolder.getKey();
        if (key != null) {
            password.setId(key.longValue());
        }
        return password;
    }

    @Override
    public void update(Password password) {
        jdbcTemplate.update(
                "UPDATE password SET password = ?, name = ?, website = ? WHERE id = ?",
                password.getPassword(), password.getName(), password.getWebsite(), password.getId());
    }

    @Override
    public void deleteById(Long id) {
        jdbcTemplate.update("DELETE FROM password WHERE id = ?", id);
    }
}
