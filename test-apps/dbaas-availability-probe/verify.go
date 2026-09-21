package main

import (
	"context"
	"fmt"
	"io"
	"net/http"
	"os"
	"strings"
	"time"
)

func fail(errOut io.Writer, format string, args ...any) {
	fmt.Fprintf(errOut, "FIXTURE_ERROR: "+format+"\n", args...)
	os.Exit(1)
}

// probeTarget names an endpoint checked before availability measurement begins.
type probeTarget struct {
	name string
	url  string
}

// checkAllTargetsOnce runs each target's check and reports every failure from that poll.
func checkAllTargetsOnce(ctx context.Context, targets []probeTarget, checks []checkFunc, requestTimeout time.Duration) (bool, string) {
	var failures []string
	for i, check := range checks {
		callCtx, cancel := context.WithTimeout(ctx, requestTimeout)
		_, err := check(callCtx)
		cancel()
		if err != nil {
			failures = append(failures, fmt.Sprintf("%s: %v", targets[i].name, err))
		}
	}
	if len(failures) == 0 {
		return true, ""
	}
	return false, strings.Join(failures, "; ")
}

// waitStableChecks requires every target to pass for the configured number of consecutive polls.
func waitStableChecks(targets []probeTarget, checks []checkFunc, requestTimeout, pollInterval time.Duration, stableConsecutive int, overallTimeout time.Duration) error {
	ctx, cancel := context.WithTimeout(context.Background(), overallTimeout)
	defer cancel()
	consecutive := 0
	lastFailure := "no successful check yet"
	for {
		ok, failure := checkAllTargetsOnce(ctx, targets, checks, requestTimeout)
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

// runVerifyPreflight requires sustained health from both aggregator pods and the sample-service path.
func runVerifyPreflight(cfg config, out, errOut io.Writer) {
	targets := []probeTarget{
		{name: os.Getenv("AGGREGATOR_POD_1_NAME"), url: os.Getenv("AGGREGATOR_POD_1_URL")},
		{name: os.Getenv("AGGREGATOR_POD_2_NAME"), url: os.Getenv("AGGREGATOR_POD_2_URL")},
		{name: "sample-service", url: cfg.sampleServiceURL},
	}
	for _, target := range targets {
		if target.name == "" || target.url == "" {
			fail(errOut, "pre-transition target name and URL must be set")
		}
	}

	client := &http.Client{Timeout: cfg.requestTimeout}
	checks := []checkFunc{
		checkHealth(client, targets[0].url),
		checkHealth(client, targets[1].url),
		checkSamplePing(client, targets[2].url),
	}

	const pollInterval = time.Second
	const stableConsecutiveSeconds = 10
	const overallTimeout = 180 * time.Second

	if err := waitStableChecks(targets, checks, cfg.requestTimeout, pollInterval, stableConsecutiveSeconds, overallTimeout); err != nil {
		fail(errOut, "pre-transition checks did not stabilize: %v", err)
	}
	fmt.Fprintln(out, "PRE_TRANSITION_CHECKS_STABLE")
}
