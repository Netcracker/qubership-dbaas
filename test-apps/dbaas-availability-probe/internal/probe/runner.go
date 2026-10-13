package probe

import (
	"context"
	"fmt"
	"io"
	"net/http"
	"strings"
	"sync"
	"time"
)

const (
	AggregatorReady       = "aggregator-ready"
	AggregatorHealth      = "aggregator-health"
	AggregatorDatabaseGet = "aggregator-database-get"
)

// ExpectedKinds returns every probe kind the continuous run must produce.
func ExpectedKinds() []string {
	return []string{AggregatorReady, AggregatorHealth, AggregatorDatabaseGet}
}

// ContinuousConfig configures the continuous, in-cluster availability run.
type ContinuousConfig struct {
	Database       DatabaseConfig
	Interval       time.Duration
	RequestTimeout time.Duration
}

// RunContinuous runs each probe independently until the context is canceled.
func RunContinuous(ctx context.Context, out io.Writer, cfg ContinuousConfig) {
	client := &http.Client{Timeout: cfg.RequestTimeout}
	var mu sync.Mutex

	checks := map[string]CheckFunc{
		AggregatorReady:       CheckReady(client, cfg.Database.AggregatorURL),
		AggregatorHealth:      CheckHealth(client, cfg.Database.AggregatorURL),
		AggregatorDatabaseGet: CheckDatabaseGet(client, cfg.Database),
	}

	var wg sync.WaitGroup
	for name, fn := range checks {
		wg.Add(1)
		go func(name string, fn CheckFunc) {
			defer wg.Done()
			runLoop(ctx, out, &mu, name, fn, cfg.Interval, cfg.RequestTimeout)
		}(name, fn)
	}
	wg.Wait()
}

// runLoop records request timeouts and panics instead of stopping the probe process.
func runLoop(ctx context.Context, out io.Writer, mu *sync.Mutex, name string, fn CheckFunc, interval, timeout time.Duration) {
	ticker := time.NewTicker(interval)
	defer ticker.Stop()

	runOnce := func() {
		defer func() {
			if r := recover(); r != nil {
				writeResult(out, mu, Record{
					Timestamp: time.Now().UTC().Format(TimestampLayout),
					Probe:     name,
					Success:   false,
					Error:     "panic during probe execution",
				})
			}
		}()
		callCtx, cancel := context.WithTimeout(ctx, timeout)
		defer cancel()
		runProbe(callCtx, out, mu, name, fn)
	}

	runOnce()
	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			runOnce()
		}
	}
}

// PreflightTarget names an endpoint checked before availability measurement begins.
type PreflightTarget struct {
	Name  string
	Check CheckFunc
}

func checkAllTargetsOnce(ctx context.Context, targets []PreflightTarget, requestTimeout time.Duration) (bool, string) {
	var failures []string
	for _, target := range targets {
		callCtx, cancel := context.WithTimeout(ctx, requestTimeout)
		_, err := target.Check(callCtx)
		cancel()
		if err != nil {
			failures = append(failures, fmt.Sprintf("%s: %v", target.Name, err))
		}
	}
	if len(failures) == 0 {
		return true, ""
	}
	return false, strings.Join(failures, "; ")
}

// RunPreflight requires each target to pass for the configured number of consecutive polls.
func RunPreflight(ctx context.Context, targets []PreflightTarget, requestTimeout, pollInterval time.Duration, stableConsecutive int, overallTimeout time.Duration) error {
	ctx, cancel := context.WithTimeout(ctx, overallTimeout)
	defer cancel()
	consecutive := 0
	lastFailure := "no successful check yet"
	for {
		ok, failure := checkAllTargetsOnce(ctx, targets, requestTimeout)
		if ctx.Err() != nil {
			if lastFailure == "no successful check yet" && !ok {
				lastFailure = failure
			}
			return fmt.Errorf("did not reach %d consecutive healthy checks within %s: %s", stableConsecutive, overallTimeout, lastFailure)
		}
		if ok {
			consecutive++
		} else {
			consecutive = 0
			lastFailure = failure
		}
		if consecutive >= stableConsecutive {
			return nil
		}
		select {
		case <-ctx.Done():
			return fmt.Errorf("did not reach %d consecutive healthy checks within %s: %s", stableConsecutive, overallTimeout, lastFailure)
		case <-time.After(pollInterval):
		}
	}
}
