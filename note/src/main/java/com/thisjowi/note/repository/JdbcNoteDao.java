package com.thisjowi.note.repository;

import com.thisjowi.note.entity.Note;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public class JdbcNoteDao implements NoteDao {

    private final JdbcTemplate jdbcTemplate;

    private final RowMapper<Note> rowMapper = (rs, rowNum) -> {
        Note note = new Note();
        note.setId(rs.getLong("id"));
        note.setContent(rs.getString("content"));
        note.setTitle(rs.getString("title"));
        Timestamp ts = rs.getTimestamp("created_at");
        if (ts != null) {
            note.setCreatedAt(ts.toLocalDateTime());
        }
        note.setUserId(rs.getString("user_id"));
        note.setVersion(rs.getLong("version"));
        return note;
    };

    public JdbcNoteDao(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public List<Note> findAll() {
        return jdbcTemplate.query("SELECT * FROM notes ORDER BY created_at DESC", rowMapper);
    }

    @Override
    public List<Note> findByUserId(String userId) {
        return jdbcTemplate.query("SELECT * FROM notes WHERE user_id = ? ORDER BY created_at DESC",
                rowMapper, userId);
    }

    @Override
    public List<Note> findByTitleIgnoreCaseContaining(String title) {
        return jdbcTemplate.query("SELECT * FROM notes WHERE LOWER(title) LIKE LOWER(?) ORDER BY created_at DESC",
                rowMapper, "%" + title + "%");
    }

    @Override
    public List<Note> findByTitleIgnoreCaseContainingAndUserId(String title, String userId) {
        return jdbcTemplate.query(
                "SELECT * FROM notes WHERE user_id = ? AND LOWER(title) LIKE LOWER(?) ORDER BY created_at DESC",
                rowMapper, userId, "%" + title + "%");
    }

    @Override
    public Optional<Note> findById(Long id) {
        return jdbcTemplate.query("SELECT * FROM notes WHERE id = ?", rowMapper, id)
                .stream().findFirst();
    }

    @Override
    public Optional<Note> findByTitleIgnoreCase(String title) {
        return jdbcTemplate.query("SELECT * FROM notes WHERE LOWER(title) = LOWER(?)", rowMapper, title)
                .stream().findFirst();
    }

    @Override
    public Optional<Note> findByTitleIgnoreCaseAndUserId(String title, String userId) {
        return jdbcTemplate.query(
                "SELECT * FROM notes WHERE user_id = ? AND LOWER(title) = LOWER(?)", rowMapper, userId, title)
                .stream().findFirst();
    }

    @Override
    public Optional<Note> findByCreatedAt(LocalDateTime createdAt) {
        return jdbcTemplate.query("SELECT * FROM notes WHERE created_at = ?", rowMapper,
                Timestamp.valueOf(createdAt)).stream().findFirst();
    }

    @Override
    public Note insert(Note note) {
        if (note.getCreatedAt() == null) {
            note.setCreatedAt(LocalDateTime.now());
        }
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO notes (content, title, created_at, user_id, version) VALUES (?, ?, ?, ?, ?)",
                    new String[]{"id"});
            ps.setString(1, note.getContent());
            ps.setString(2, note.getTitle());
            ps.setTimestamp(3, Timestamp.valueOf(note.getCreatedAt()));
            ps.setString(4, note.getUserId());
            ps.setLong(5, note.getVersion() != null ? note.getVersion() : 0L);
            return ps;
        }, keyHolder);

        Number key = keyHolder.getKey();
        if (key != null) {
            note.setId(key.longValue());
        }
        return note;
    }

    @Override
    public void update(Note note) {
        jdbcTemplate.update(
                "UPDATE notes SET content = ?, title = ?, user_id = ?, version = ? WHERE id = ?",
                note.getContent(), note.getTitle(), note.getUserId(),
                note.getVersion(), note.getId());
    }

    @Override
    public void deleteById(Long id) {
        jdbcTemplate.update("DELETE FROM notes WHERE id = ?", id);
    }

    @Override
    public void delete(Note note) {
        if (note.getId() != null) {
            deleteById(note.getId());
        }
    }

    @Override
    public boolean existsById(Long id) {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM notes WHERE id = ?", Integer.class, id);
        return count != null && count > 0;
    }
}
