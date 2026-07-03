package logfields

import (
	"fmt"
	"strings"
)

// Format builds a human-readable message with trailing key=value pairs.
// The NDJSON formatter promotes those pairs to top-level JSON fields.
func Format(message string, pairs ...any) string {
	if len(pairs) == 0 {
		return message
	}
	var b strings.Builder
	b.WriteString(message)
	for i := 0; i+1 < len(pairs); i += 2 {
		b.WriteByte(' ')
		b.WriteString(fmt.Sprintf("%v=%v", pairs[i], pairs[i+1]))
	}
	return b.String()
}

// Err appends error=<err> to the formatted message.
func Err(message string, err error, pairs ...any) string {
	all := make([]any, 0, len(pairs)+2)
	all = append(all, pairs...)
	all = append(all, "error", err)
	return Format(message, all...)
}
