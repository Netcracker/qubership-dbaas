package main

import (
	"fmt"
	"strings"

	"github.com/go-logr/logr"
	"github.com/netcracker/qubership-core-lib-go/v3/logging"
)

// logrAdapter bridges controller-runtime's logr.LogSink to the project's
// custom logging.Logger.  controller-runtime verbosity levels: 0 = info,
// 1+ = debug.
type logrAdapter struct {
	logger logging.Logger
	kvs    []interface{}
}

func newLogrLogger(logger logging.Logger) logr.Logger {
	return logr.New(&logrAdapter{logger: logger})
}

func (a *logrAdapter) Init(_ logr.RuntimeInfo) {}

func (a *logrAdapter) Enabled(level int) bool {
	if level > 0 {
		return a.logger.GetLevel() >= logging.LvlDebug
	}
	return true
}

func (a *logrAdapter) Info(level int, msg string, keysAndValues ...interface{}) {
	full := formatMessage(msg, append(a.kvs, keysAndValues...))
	if level > 0 {
		a.logger.Debugf("%s", full)
	} else {
		a.logger.Infof("%s", full)
	}
}

func (a *logrAdapter) Error(err error, msg string, keysAndValues ...interface{}) {
	full := formatMessage(msg, append(a.kvs, keysAndValues...))
	if err != nil {
		a.logger.Errorf("%s: %v", full, err)
	} else {
		a.logger.Errorf("%s", full)
	}
}

func (a *logrAdapter) WithValues(keysAndValues ...interface{}) logr.LogSink {
	merged := make([]interface{}, len(a.kvs)+len(keysAndValues))
	copy(merged, a.kvs)
	copy(merged[len(a.kvs):], keysAndValues)
	return &logrAdapter{logger: a.logger, kvs: merged}
}

func (a *logrAdapter) WithName(name string) logr.LogSink {
	return &logrAdapter{logger: logging.GetLogger(name), kvs: a.kvs}
}

func formatMessage(msg string, kvs []interface{}) string {
	if len(kvs) == 0 {
		return msg
	}
	var b strings.Builder
	b.WriteString(msg)
	for i := 0; i+1 < len(kvs); i += 2 {
		b.WriteByte(' ')
		b.WriteString(fmt.Sprintf("%v=%v", kvs[i], kvs[i+1]))
	}
	return b.String()
}
