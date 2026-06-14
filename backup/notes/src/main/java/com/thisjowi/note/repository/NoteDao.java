package com.thisjowi.note.repository;

import com.thisjowi.note.entity.Note;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface NoteDao {
    List<Note> findAll();
    List<Note> findByUserId(String userId);
    List<Note> findByTitleIgnoreCaseContaining(String title);
    List<Note> findByTitleIgnoreCaseContainingAndUserId(String title, String userId);
    Optional<Note> findById(Long id);
    Optional<Note> findByTitleIgnoreCase(String title);
    Optional<Note> findByTitleIgnoreCaseAndUserId(String title, String userId);
    Optional<Note> findByCreatedAt(LocalDateTime createdAt);
    Note insert(Note note);
    void update(Note note);
    void deleteById(Long id);
    void delete(Note note);
    boolean existsById(Long id);
}
