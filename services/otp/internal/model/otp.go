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
	Name      string `json:"name" binding:"max=255"`
	Issuer    string `json:"issuer" binding:"max=255"`
	Secret    string `json:"secret" binding:"max=512"`
	Digits    int    `json:"digits" binding:"min=1,max=8"`
	Period    int    `json:"period" binding:"min=1,max=3600"`
	Algorithm string `json:"algorithm" binding:"max=20"`
	Type      string `json:"type" binding:"required,max=10"`
}
