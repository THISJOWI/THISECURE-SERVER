package model

type Password struct {
	ID       int64  `json:"id" db:"id"`
	Password string `json:"password" db:"password"`
	Name     string `json:"title" db:"name"`
	Website  string `json:"website" db:"website"`
	Username string `json:"username" db:"username"`
	UserID   string `json:"userId" db:"user_id"`
}

type PasswordRequest struct {
	Password string `json:"password" binding:"required,max=4096"`
	Name     string `json:"title" binding:"required,max=255"`
	Website  string `json:"website" binding:"max=2048"`
	Username string `json:"username" binding:"max=255"`
}

type ImportResult struct {
	Imported int `json:"imported"`
	Skipped  int `json:"skipped"`
	Errors   int `json:"errors"`
	Total    int `json:"total"`
}

type DuplicateGroup struct {
	Name     string  `json:"name"`
	Website  string  `json:"website"`
	Username string  `json:"username"`
	Count    int     `json:"count"`
	IDs      []int64 `json:"ids"`
}

type DuplicateAnalysis struct {
	Groups []DuplicateGroup `json:"groups"`
	Total  int              `json:"total"`
}
