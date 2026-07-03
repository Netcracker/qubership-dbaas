package logformat

import (
	"bytes"
	"encoding/json"
	"os"
	"regexp"
	"strings"
	"time"

	"github.com/netcracker/qubership-core-lib-go/v3/logging"
)

var kvSuffix = regexp.MustCompile(`(?:^|\s)([A-Za-z_][A-Za-z0-9_.-]*)=((?:'[^']*')|(?:"[^"]*")|(?:\S+))`)

// Init selects NDJSON or legacy text output from LOG_FORMAT (default json).
func Init() {
	Configure()
}

// Configure selects NDJSON or legacy text output from LOG_FORMAT (default json).
func Configure() {
	switch strings.ToLower(strings.TrimSpace(os.Getenv("LOG_FORMAT"))) {
	case "", "json":
		logging.SetLogFormat(ndjsonFormat)
	case "text":
		// Keep library default bracket text formatter.
	default:
		logging.SetLogFormat(ndjsonFormat)
	}
}

func ndjsonFormat(r *logging.Record) []byte {
	message, fields := splitMessageFields(r.Message)
	record := map[string]any{
		"time":    r.Time.UTC().Format(time.RFC3339Nano),
		"level":   strings.ToUpper(r.Lvl.String()),
		"message": message,
	}
	if r.PackageName != "" {
		record["class"] = r.PackageName
	}
	putContextField(record, "request_id", logging.GetValueOrPlaceholder(r.Ctx, logging.RequestIdContextName))
	putContextField(record, "tenant_id", logging.GetValueOrPlaceholder(r.Ctx, logging.TenantContextName))
	putContextField(record, "x_channel_request_id", logging.GetValueOrPlaceholder(r.Ctx, logging.ChannelRequestIdContextName))
	for k, v := range fields {
		record[k] = v
	}
	var buf bytes.Buffer
	enc := json.NewEncoder(&buf)
	enc.SetEscapeHTML(false)
	_ = enc.Encode(record)
	return buf.Bytes()
}

func putContextField(record map[string]any, key, value string) {
	if value != "" && value != "-" {
		record[key] = value
	}
}

func splitMessageFields(message string) (string, map[string]string) {
	fields := make(map[string]string)
	matches := kvSuffix.FindAllStringSubmatchIndex(message, -1)
	if len(matches) == 0 {
		return strings.TrimSpace(message), fields
	}
	cut := len(message)
	for _, m := range matches {
		if m[0] < cut {
			cut = m[0]
		}
		name := message[m[2]:m[3]]
		raw := message[m[4]:m[5]]
		fields[name] = strings.Trim(raw, `"'`)
	}
	return strings.TrimSpace(message[:cut]), fields
}
