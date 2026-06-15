package model

import "time"

type Note struct {
	ID        int64     `json:"id" db:"id"`
	Content   string    `json:"content" db:"content"`
	Title     string    `json:"title" db:"title"`
	CreatedAt time.Time `json:"createdAt" db:"created_at"`
	UserID    string    `json:"userId" db:"user_id"`
	Version   int64     `json:"version" db:"version"`
}

type NoteRequest struct {
	Title   string `json:"title" binding:"required,max=255"`
	Content string `json:"content" binding:"max=65536"`
}

type ImportResult struct {
	Imported int `json:"imported"`
	Skipped  int `json:"skipped"`
	Errors   int `json:"errors"`
	Total    int `json:"total"`
}
