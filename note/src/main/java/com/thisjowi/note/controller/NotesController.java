package com.thisjowi.note.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import io.swagger.v3.oas.annotations.tags.Tag;
import com.thisjowi.note.entity.Note;
import com.thisjowi.note.repository.NoteDao;
import com.thisjowi.note.service.NoteService;
import com.thisjowi.note.service.AuthenticationClient;

import java.util.List;
import java.util.Optional;

@RestController
@RequestMapping("/v1/notes")
@Tag(name = "Notes", description = "Encrypted note storage with CRUD operations and user-scoped access")
public class NotesController {

    @Autowired
    private NoteService notesService;

    @Autowired
    private NoteDao noteDao;

    @Autowired
    private AuthenticationClient authenticationClient;

    private Long extractUserIdFromToken(String authHeader) {
        if (authHeader == null || authHeader.isEmpty()) {
            return null;
        }

        if (!authHeader.startsWith("Bearer ")) {
            return null;
        }

        try {
            Long userId = authenticationClient.getUserIdFromToken(authHeader);
            return (userId != null && userId >= 0) ? userId : null;
        } catch (Exception e) {
            System.err.println("Error extracting userId from token: " + e.getMessage());
            return null;
        }
    }

    @PostMapping
    public ResponseEntity<Note> createNote(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authHeader,
            @RequestBody Note note) {
        Long userId = extractUserIdFromToken(authHeader);
        if (userId == null) {
            return ResponseEntity.status(401).build();
        }

        note.setUserId(userId);
        Note savedNote = notesService.saveNoteWithDeduplication(note);
        return ResponseEntity.ok(savedNote);
    }

    @GetMapping
    public ResponseEntity<List<Note>> getAllNotes(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authHeader) {
        Long userId = extractUserIdFromToken(authHeader);
        if (userId == null) {
            return ResponseEntity.status(401).build();
        }

        List<Note> notes = notesService.getNotesByUserId(userId);
        return ResponseEntity.ok(notes);
    }

    @GetMapping("/search")
    public ResponseEntity<List<Note>> searchNotes(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authHeader,
            @RequestParam(value = "title", required = false) String title) {
        Long userId = extractUserIdFromToken(authHeader);
        if (userId == null) {
            return ResponseEntity.status(401).build();
        }

        List<Note> notes = notesService.searchNotesByTitleAndUserId(title, userId);
        return ResponseEntity.ok(notes);
    }

    @GetMapping("/{title}")
    public ResponseEntity<Note> getNoteByTitle(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authHeader,
            @PathVariable String title) {
        Long userId = extractUserIdFromToken(authHeader);
        if (userId == null) {
            return ResponseEntity.status(401).build();
        }

        Optional<Note> noteOpt = notesService.getNoteByTitleAndUserId(title, userId);
        return noteOpt.map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PutMapping("/{title}")
    public ResponseEntity<Note> updateNote(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authHeader,
            @PathVariable String title,
            @RequestBody Note noteDetails) {
        Long userId = extractUserIdFromToken(authHeader);
        if (userId == null) {
            return ResponseEntity.status(401).build();
        }

        Optional<Note> updated = notesService.updateNoteByTitleAndUserId(title, noteDetails, userId);
        return updated.map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteNote(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authHeader,
            @PathVariable Long id) {
        Long userId = extractUserIdFromToken(authHeader);
        if (userId == null) {
            return ResponseEntity.status(401).build();
        }

        Optional<Note> noteOpt = noteDao.findById(id);
        if (noteOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        Note note = noteOpt.get();
        if (!note.getUserId().equals(userId)) {
            return ResponseEntity.status(403).build();
        }

        boolean deleted = notesService.deleteNoteById(id);
        if (deleted) {
            return ResponseEntity.noContent().build();
        } else {
            return ResponseEntity.notFound().build();
        }
    }
}
