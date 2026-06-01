package com.thisjowi.password.Repository;

import com.thisjowi.password.Entity.Password;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class PasswordDao {

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public PasswordDao(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private static final String ALL_COLUMNS = "id, password, name, website, user_id";

    private static final RowMapper<Password> ROW_MAPPER = (rs, rowNum) -> {
        Password p = new Password();
        p.setId(rs.getLong("id"));
        p.setPassword(rs.getString("password"));
        p.setName(rs.getString("name"));
        p.setWebsite(rs.getString("website"));
        p.setUserId(rs.getObject("user_id", Long.class));
        return p;
    };

    public Password insert(Password password) {
        String sql = "INSERT INTO password (password, name, website, user_id) VALUES (:password, :name, :website, :userId)";
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("password", password.getPassword())
                .addValue("name", password.getName())
                .addValue("website", password.getWebsite())
                .addValue("userId", password.getUserId());
        GeneratedKeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(sql, params, keyHolder, new String[]{"id"});
        password.setId(keyHolder.getKey().longValue());
        return password;
    }

    public void update(Password password) {
        String sql = "UPDATE password SET password = :password, name = :name, website = :website, user_id = :userId WHERE id = :id";
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("password", password.getPassword())
                .addValue("name", password.getName())
                .addValue("website", password.getWebsite())
                .addValue("userId", password.getUserId())
                .addValue("id", password.getId());
        jdbcTemplate.update(sql, params);
    }

    public Optional<Password> findById(Long id) {
        String sql = "SELECT " + ALL_COLUMNS + " FROM password WHERE id = :id";
        List<Password> results = jdbcTemplate.query(sql, new MapSqlParameterSource("id", id), ROW_MAPPER);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    public List<Password> findByUserId(Long userId) {
        String sql = "SELECT " + ALL_COLUMNS + " FROM password WHERE user_id = :userId";
        return jdbcTemplate.query(sql, new MapSqlParameterSource("userId", userId), ROW_MAPPER);
    }

    public Optional<Password> findByUserIdAndNameAndWebsite(Long userId, String name, String website) {
        String sql = "SELECT " + ALL_COLUMNS + " FROM password WHERE user_id = :userId AND name = :name AND website = :website";
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("name", name)
                .addValue("website", website);
        List<Password> results = jdbcTemplate.query(sql, params, ROW_MAPPER);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    public void deleteById(Long id) {
        String sql = "DELETE FROM password WHERE id = :id";
        jdbcTemplate.update(sql, new MapSqlParameterSource("id", id));
    }

    public boolean existsById(Long id) {
        String sql = "SELECT COUNT(*) FROM password WHERE id = :id";
        Integer count = jdbcTemplate.queryForObject(sql, new MapSqlParameterSource("id", id), Integer.class);
        return count != null && count > 0;
    }
}
