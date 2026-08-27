package app

import (
	"encoding/json"
	"log"
	"net/http"
	"net/url"
)

const errorResponseMessage = "request failed"

func sanitizeURL(raw string) string {
	parsed, err := url.Parse(raw)
	if err != nil {
		return "<invalid-url>"
	}
	if parsed.User == nil {
		return raw
	}
	username := parsed.User.Username()
	if username == "" {
		parsed.User = url.UserPassword("", "xxxxx")
	} else {
		parsed.User = url.UserPassword(username, "xxxxx")
	}
	return parsed.String()
}

func writeJSON(w http.ResponseWriter, status int, body interface{}) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	if err := json.NewEncoder(w).Encode(body); err != nil {
		log.Printf("write response: %v", err)
	}
}

func writeError(w http.ResponseWriter, status int, err error) {
	log.Printf("request failed: %v", err)
	writeJSON(w, status, map[string]string{"error": errorResponseMessage})
}
