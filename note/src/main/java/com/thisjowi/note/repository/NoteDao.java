package com.thisjowi.note.repository;

import com.thisjowi.note.entity.Note;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public class NoteDao {

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public NoteDao(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private static final String ALL_COLUMNS = "id, content, title, created_at, user_id, version";

    private static final RowMapper<Note> ROW_MAPPER = (rs, rowNum) -> {
        Note n = new Note();
        n.setId(rs.getLong("id"));
        n.setContent(rs.getString("content"));
        n.setTitle(rs.getString("title"));
        Timestamp ts = rs.getTimestamp("created_at");
        if (ts != null) {
            n.setCreatedAt(ts.toLocalDateTime());
        }
        n.setUserId(rs.getObject("user_id", Long.class));
        n.setVersion(rs.getLong("version"));
        return n;
    };

    public Note insert(Note note) {
        String sql = "INSERT INTO notes (content, title, created_at, user_id, version) VALUES (:content, :title, :createdAt, :userId, 0)";
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("content", note.getContent())
                .addValue("title", note.getTitle())
                .addValue("createdAt", note.getCreatedAt() != null ? Timestamp.valueOf(note.getCreatedAt()) : null)
                .addValue("userId", note.getUserId());
        GeneratedKeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(sql, params, keyHolder, new String[]{"id"});
        note.setId(keyHolder.getKey().longValue());
        note.setVersion(0L);
        return note;
    }

    public void update(Note note) {
        String sql = "UPDATE notes SET content = :content, title = :title, created_at = :createdAt, user_id = :userId, version = version + 1 WHERE id = :id AND version = :version";
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("content", note.getContent())
                .addValue("title", note.getTitle())
                .addValue("createdAt", note.getCreatedAt() != null ? Timestamp.valueOf(note.getCreatedAt()) : null)
                .addValue("userId", note.getUserId())
                .addValue("id", note.getId())
                .addValue("version", note.getVersion());
        int affected = jdbcTemplate.update(sql, params);
        if (affected == 0) {
            throw new OptimisticLockingFailureException(
                "Note with id " + note.getId() + " was updated or deleted by another transaction");
        }
        note.setVersion(note.getVersion() + 1);
    }

    public Optional<Note> findById(Long id) {
        String sql = "SELECT " + ALL_COLUMNS + " FROM notes WHERE id = :id";
        List<Note> results = jdbcTemplate.query(sql, new MapSqlParameterSource("id", id), ROW_MAPPER);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    public List<Note> findByUserId(Long userId) {
        String sql = "SELECT " + ALL_COLUMNS + " FROM notes WHERE user_id = :userId";
        return jdbcTemplate.query(sql, new MapSqlParameterSource("userId", userId), ROW_MAPPER);
    }

    public List<Note> findByTitleIgnoreCaseContainingAndUserId(String title, Long userId) {
        String sql = "SELECT " + ALL_COLUMNS + " FROM notes WHERE LOWER(title) LIKE LOWER(CONCAT('%', :title, '%')) AND user_id = :userId";
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("title", title)
                .addValue("userId", userId);
        return jdbcTemplate.query(sql, params, ROW_MAPPER);
    }

    public Optional<Note> findByTitleIgnoreCaseAndUserId(String title, Long userId) {
        String sql = "SELECT " + ALL_COLUMNS + " FROM notes WHERE LOWER(title) = LOWER(:title) AND user_id = :userId";
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("title", title)
                .addValue("userId", userId);
        List<Note> results = jdbcTemplate.query(sql, params, ROW_MAPPER);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    public Optional<Note> findByCreatedAt(LocalDateTime createdAt) {
        String sql = "SELECT " + ALL_COLUMNS + " FROM notes WHERE created_at = :createdAt";
        List<Note> results = jdbcTemplate.query(sql,
                new MapSqlParameterSource("createdAt", Timestamp.valueOf(createdAt)), ROW_MAPPER);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    public Optional<Note> findByTitleIgnoreCase(String title) {
        String sql = "SELECT " + ALL_COLUMNS + " FROM notes WHERE LOWER(title) = LOWER(:title)";
        List<Note> results = jdbcTemplate.query(sql, new MapSqlParameterSource("title", title), ROW_MAPPER);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    public List<Note> findByTitleIgnoreCaseContaining(String title) {
        String sql = "SELECT " + ALL_COLUMNS + " FROM notes WHERE LOWER(title) LIKE LOWER(CONCAT('%', :title, '%'))";
        return jdbcTemplate.query(sql, new MapSqlParameterSource("title", title), ROW_MAPPER);
    }

    public void deleteById(Long id) {
        String sql = "DELETE FROM notes WHERE id = :id";
        jdbcTemplate.update(sql, new MapSqlParameterSource("id", id));
    }

    public void delete(Note note) {
        deleteById(note.getId());
    }

    public boolean existsById(Long id) {
        String sql = "SELECT COUNT(*) FROM notes WHERE id = :id";
        Integer count = jdbcTemplate.queryForObject(sql, new MapSqlParameterSource("id", id), Integer.class);
        return count != null && count > 0;
    }

    public List<Note> findAll() {
        String sql = "SELECT " + ALL_COLUMNS + " FROM notes";
        return jdbcTemplate.query(sql, ROW_MAPPER);
    }
}
