package model

type Passkey struct {
	ID              int64    `json:"id" db:"id"`
	CredentialID    string   `json:"credentialId" db:"credential_id"`
	PublicKey       string   `json:"publicKey" db:"public_key"`
	RpID            string   `json:"rpId" db:"rp_id"`
	RpName          string   `json:"rpName" db:"rp_name"`
	UserHandle      string   `json:"userHandle" db:"user_handle"`
	UserDisplayName string   `json:"userDisplayName" db:"user_display_name"`
	SignCount       int64    `json:"signCount" db:"sign_count"`
	Name            string   `json:"name" db:"name"`
	Transports      []string `json:"transports" db:"transports"`
	CredentialType  string   `json:"credentialType" db:"credential_type"`
	BackupEligible  bool     `json:"backupEligible" db:"backup_eligible"`
	BackupState     bool     `json:"backupState" db:"backup_state"`
	UserID          string   `json:"userId" db:"user_id"`
}

type PasskeyRequest struct {
	CredentialID    string   `json:"credentialId" binding:"required,max=1024"`
	PublicKey       string   `json:"publicKey" binding:"required,max=8192"`
	RpID            string   `json:"rpId" binding:"max=512"`
	RpName          string   `json:"rpName" binding:"max=255"`
	UserHandle      string   `json:"userHandle" binding:"max=1024"`
	UserDisplayName string   `json:"userDisplayName" binding:"max=255"`
	SignCount       int64    `json:"signCount"`
	Name            string   `json:"name" binding:"required,max=255"`
	Transports      []string `json:"transports"`
	CredentialType  string   `json:"credentialType" binding:"max=64"`
	BackupEligible  bool     `json:"backupEligible"`
	BackupState     bool     `json:"backupState"`
}
