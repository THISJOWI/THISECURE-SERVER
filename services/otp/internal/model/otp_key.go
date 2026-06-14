package model

import "time"

type OtpKey struct {
	ID        int64     `json:"id" db:"id"`
	UserID    string    `json:"userId" db:"user_id"`
	Otp       string    `json:"otp" db:"otp"`
	CreatedAt time.Time `json:"createdAt" db:"created_at"`
}
