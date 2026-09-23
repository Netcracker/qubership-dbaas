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

// Probe kind names. These are the exact "probe" field values every Record carries, and must match
// the evaluator's expected-kind list.
const (
	AggregatorReady    = "aggregator-ready"
	AggregatorHealth   = "aggregator-health"
	SamplePostgresPing = "sample-postgres-ping"
)

// ExpectedKinds returns every probe kind the continuous run must produce.
func ExpectedKinds() []string {
	return []string{AggregatorReady, AggregatorHealth, SamplePostgresPing}
}

// ContinuousConfig configures the continuous, in-cluster availability run.
type ContinuousConfig struct {
	AggregatorURL    string
	SampleServiceURL string
	Interval         time.Duration
	RequestTimeout   time.Duration
}

// RunContinuous schedules the three probe kinds on independent tickers and returns only when ctx
// is done. A failed sample is recorded and probing continues (see runLoop) so the final log
// captures the complete outage window instead of stopping at the first failure.
func RunContinuous(ctx context.Context, out io.Writer, cfg ContinuousConfig) {
	client := &http.Client{Timeout: cfg.RequestTimeout}
	var mu sync.Mutex

	checks := map[string]CheckFunc{
		AggregatorReady:    CheckReady(client, cfg.AggregatorURL),
		AggregatorHealth:   CheckHealth(client, cfg.AggregatorURL),
		SamplePostgresPing: CheckSamplePing(client, cfg.SampleServiceURL),
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

// runLoop fires fn once per tick until ctx is done. Each call gets its own bounded sub-context so
// one hung request cannot delay the next tick indefinitely. A panic inside fn (there should never
// be one, but a probe process going silent mid-transition is worse than a logged failure) is
// recovered and recorded as a failed sample rather than crashing the whole probe.
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

// checkAllTargetsOnce runs each target's check and reports every failure from that poll.
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

// RunPreflight requires every target to pass for stableConsecutive consecutive polls, within
// overallTimeout. It is the pre-transition prerequisite: both aggregator pods and the
// sample-service path must be healthy before the measured baseline starts, so a cached
// PROBLEM status from the initial release is classified as a setup failure instead of transition
// downtime.
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
