package models

type SyncEvent struct {
	EventID     string      `json:"eventId"`
	UserID      string      `json:"userId"`
	ServiceName string      `json:"serviceName"`
	Action      string      `json:"action"`
	Payload     interface{} `json:"payload"`
	Timestamp   int64       `json:"timestamp"`
}

type UserRegisteredEvent struct {
	UserID    string `json:"userId"`
	Email     string `json:"email"`
	EventType string `json:"eventType"`
	Timestamp int64  `json:"timestamp"`
}

type OtpCreatedEvent struct {
	OtpID     int64  `json:"otpId"`
	UserID    string `json:"userId"`
	Email     string `json:"email"`
	Type      string `json:"type"`
	EventType string `json:"eventType"`
	Timestamp int64  `json:"timestamp"`
	ExpiresAt int64  `json:"expiresAt"`
}
