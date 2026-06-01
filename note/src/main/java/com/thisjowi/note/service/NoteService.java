package com.thisjowi.note.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.dao.DataIntegrityViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.thisjowi.note.Utils.EncryptionUtil;
import com.thisjowi.note.entity.Note;
import com.thisjowi.note.repository.NoteDao;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Service
public class NoteService {

    private static final Logger logger = LoggerFactory.getLogger(NoteService.class);
    private final NoteDao noteDao;

    public NoteService(NoteDao noteDao) {
        this.noteDao = noteDao;
    }

    @Transactional(readOnly = true)
    public List<Note> getAllNotes() {
        List<Note> notes = noteDao.findAll();
        return notes.stream().map(this::decryptNote).toList();
    }

    @Transactional(readOnly = true)
    public List<Note> searchNotesByTitle(String title) {
        if (title == null) title = "";
        List<Note> notes = noteDao.findByTitleIgnoreCaseContaining(title);
        return notes.stream().map(this::decryptNote).toList();
    }

    @Transactional
    public Note saveNote(Note note) {
        String originalTitle = note.getTitle();

        note.setTitle(EncryptionUtil.encrypt(note.getTitle()));
        note.setContent(EncryptionUtil.encrypt(note.getContent()));

        try {
            Note saved = noteDao.insert(note);

            Note response = new Note();
            response.setId(saved.getId());
            response.setUserId(saved.getUserId());
            response.setCreatedAt(saved.getCreatedAt());
            response.setTitle(EncryptionUtil.decrypt(saved.getTitle()));
            response.setContent(EncryptionUtil.decrypt(saved.getContent()));
            return response;
        } catch (DataIntegrityViolationException e) {
            logger.info("Constraint violation detected - note with title '{}' for user {} already exists",
                       originalTitle, note.getUserId());

            Optional<Note> existing = noteDao.findByTitleIgnoreCaseAndUserId(
                originalTitle,
                note.getUserId()
            );

            if (existing.isPresent()) {
                return decryptNote(existing.get());
            } else {
                throw e;
            }
        }
    }

    @Transactional
    public Note saveNoteWithDeduplication(Note note) {
        if (note.getUserId() == null) {
            throw new IllegalArgumentException("UserId is required");
        }

        Long userId = note.getUserId();
        String titleToCheck = note.getTitle() != null ? note.getTitle().trim() : "";

        if (titleToCheck.isEmpty()) {
            throw new IllegalArgumentException("Note title is required");
        }

        Optional<Note> existingOptional = noteDao.findByTitleIgnoreCaseAndUserId(titleToCheck, userId);

        if (existingOptional.isPresent()) {
            Note existing = existingOptional.get();
            Long existingId = existing.getId();
            logger.info("Duplicate note detected for user {}, updating existing note id: {}", userId, existingId);

            if (note.getContent() != null && !note.getContent().isEmpty()) {
                existing.setTitle(EncryptionUtil.encrypt(titleToCheck));
                existing.setContent(EncryptionUtil.encrypt(note.getContent()));
                existing.setUserId(userId);

                noteDao.update(existing);

                Note response = new Note();
                response.setId(existing.getId());
                response.setUserId(existing.getUserId());
                response.setCreatedAt(existing.getCreatedAt());
                response.setTitle(EncryptionUtil.decrypt(existing.getTitle()));
                response.setContent(EncryptionUtil.decrypt(existing.getContent()));
                return response;
            } else {
                return decryptNote(existing);
            }
        } else {
            logger.info("No duplicate found, creating new note for user {}", userId);
            return saveNote(note);
        }
    }

    @Transactional(readOnly = true)
    public Optional<Note> getNoteByTitle(String title) {
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("Cannot search for a blank note");
        }
        return noteDao.findByTitleIgnoreCase(title)
                .map(this::decryptNote);
    }

    public Optional<Note> getNoteByCretedAt(LocalDateTime createdAt) {
        return noteDao.findByCreatedAt(createdAt);
    }

    @Transactional(readOnly = true)
    public List<Note> getNotesByUserId(Long userId) {
        List<Note> notes = noteDao.findByUserId(userId);
        return notes.stream().map(this::decryptNote).toList();
    }

    @Transactional
    public boolean deleteNoteById(Long id) {
        if (noteDao.existsById(id)) {
            noteDao.deleteById(id);
            return true;
        }
        return false;
    }

    @Transactional
    public Note updateNote(Note note) {
        if (note.getTitle() != null) {
            note.setTitle(EncryptionUtil.encrypt(note.getTitle()));
        }
        if (note.getContent() != null) {
            note.setContent(EncryptionUtil.encrypt(note.getContent()));
        }
        noteDao.update(note);

        Note response = new Note();
        response.setId(note.getId());
        response.setUserId(note.getUserId());
        response.setCreatedAt(note.getCreatedAt());

        if (note.getTitle() != null) {
            response.setTitle(EncryptionUtil.decrypt(note.getTitle()));
        }
        if (note.getContent() != null) {
            response.setContent(EncryptionUtil.decrypt(note.getContent()));
        }
        return response;
    }

    @Transactional
    public boolean deleteNoteByTitle(String title) {
        if (title == null || title.isBlank()) return false;
        Optional<Note> existing = noteDao.findByTitleIgnoreCase(title);
        if (existing.isPresent()) {
            noteDao.delete(existing.get());
            return true;
        }
        return false;
    }

    @Transactional
    public Optional<Note> updateNoteByTitle(String title, Note noteDetails) {
        if (title == null || title.isBlank()) return Optional.empty();
        Optional<Note> existingOpt = noteDao.findByTitleIgnoreCase(title);
        if (existingOpt.isPresent()) {
            Note noteToUpdate = existingOpt.get();
            if (noteDetails.getTitle() != null && !noteDetails.getTitle().isBlank()) {
                noteToUpdate.setTitle(EncryptionUtil.encrypt(noteDetails.getTitle()));
            }
            if (noteDetails.getContent() != null) {
                noteToUpdate.setContent(EncryptionUtil.encrypt(noteDetails.getContent()));
            }
            noteDao.update(noteToUpdate);

            Note response = new Note();
            response.setId(noteToUpdate.getId());
            response.setUserId(noteToUpdate.getUserId());
            response.setCreatedAt(noteToUpdate.getCreatedAt());
            response.setTitle(EncryptionUtil.decrypt(noteToUpdate.getTitle()));
            response.setContent(EncryptionUtil.decrypt(noteToUpdate.getContent()));

            return Optional.of(response);
        }
        return Optional.empty();
    }

    @Transactional(readOnly = true)
    public List<Note> searchNotesByTitleAndUserId(String title, Long userId) {
        if (title == null) title = "";
        List<Note> notes = noteDao.findByTitleIgnoreCaseContainingAndUserId(title, userId);
        return notes.stream().map(this::decryptNote).toList();
    }

    @Transactional(readOnly = true)
    public Optional<Note> getNoteByTitleAndUserId(String title, Long userId) {
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("Cannot search for a blank note");
        }
        return noteDao.findByTitleIgnoreCaseAndUserId(title, userId)
                .map(this::decryptNote);
    }

    @Transactional
    public Optional<Note> updateNoteByTitleAndUserId(String title, Note noteDetails, Long userId) {
        if (title == null || title.isBlank()) return Optional.empty();
        Optional<Note> existingOpt = noteDao.findByTitleIgnoreCaseAndUserId(title, userId);
        if (existingOpt.isPresent()) {
            Note noteToUpdate = existingOpt.get();
            if (noteDetails.getTitle() != null && !noteDetails.getTitle().isBlank()) {
                noteToUpdate.setTitle(EncryptionUtil.encrypt(noteDetails.getTitle()));
            }
            if (noteDetails.getContent() != null) {
                noteToUpdate.setContent(EncryptionUtil.encrypt(noteDetails.getContent()));
            }
            noteToUpdate.setUserId(userId);
            noteDao.update(noteToUpdate);

            Note response = new Note();
            response.setId(noteToUpdate.getId());
            response.setUserId(noteToUpdate.getUserId());
            response.setCreatedAt(noteToUpdate.getCreatedAt());
            response.setTitle(EncryptionUtil.decrypt(noteToUpdate.getTitle()));
            response.setContent(EncryptionUtil.decrypt(noteToUpdate.getContent()));

            return Optional.of(response);
        }
        return Optional.empty();
    }

    @Transactional
    public boolean deleteNoteByTitleAndUserId(String title, Long userId) {
        if (title == null || title.isBlank()) return false;
        Optional<Note> existing = noteDao.findByTitleIgnoreCaseAndUserId(title, userId);
        if (existing.isPresent()) {
            noteDao.delete(existing.get());
            return true;
        }
        return false;
    }

    private Note decryptNote(Note note) {
        Note copy = new Note();
        copy.setId(note.getId());
        copy.setUserId(note.getUserId());
        copy.setCreatedAt(note.getCreatedAt());
        copy.setTitle(EncryptionUtil.decrypt(note.getTitle()));
        copy.setContent(EncryptionUtil.decrypt(note.getContent()));
        return copy;
    }
}
