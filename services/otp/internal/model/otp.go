package model

type Otp struct {
	ID        int64   `json:"id" db:"id"`
	UserID    string  `json:"userId" db:"user_id"`
	Email     string  `json:"email" db:"email"`
	Secret    string  `json:"secret" db:"secret"`
	ExpiresAt string  `json:"expiresAt" db:"expires_at"`
	Type      string  `json:"type" db:"type"`
	Issuer    *string `json:"issuer" db:"issuer"`
	Digits    int     `json:"digits" db:"digits"`
	Period    int     `json:"period" db:"period"`
	Algorithm *string `json:"algorithm" db:"algorithm"`
	Valid     string  `json:"valid" db:"valid"`
}

type CreateOtpRequest struct {
	Name      string `json:"name"`
	Issuer    string `json:"issuer"`
	Secret    string `json:"secret"`
	Digits    int    `json:"digits"`
	Period    int    `json:"period"`
	Algorithm string `json:"algorithm"`
	Type      string `json:"type" binding:"required"`
}
