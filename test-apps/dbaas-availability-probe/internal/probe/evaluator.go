package probe

import (
	"encoding/json"
	"fmt"
	"sort"
	"strings"
	"time"
)

// ParseRecords parses one JSON Record per non-empty line, as captured from the probe container's
// stdout. A line that fails to parse is never silently dropped — it is returned in parseErrors so
// Evaluate can surface it as a run failure instead of quietly under-counting samples.
func ParseRecords(logs []byte) (records []Record, parseErrors []string) {
	for _, line := range strings.Split(string(logs), "\n") {
		line = strings.TrimSpace(line)
		if line == "" {
			continue
		}
		var rec Record
		if err := json.Unmarshal([]byte(line), &rec); err != nil {
			parseErrors = append(parseErrors, fmt.Sprintf("%q: %v", line, err))
			continue
		}
		if _, err := time.Parse(TimestampLayout, rec.Timestamp); err != nil {
			parseErrors = append(parseErrors, fmt.Sprintf("probe=%q has invalid timestamp %q: %v", rec.Probe, rec.Timestamp, err))
			continue
		}
		records = append(records, rec)
	}
	return records, parseErrors
}

// EvalParams describes the measured run the evaluator judges: the four window boundaries, which
// probe kinds must appear, the interval samples are expected at, the maximum tolerated gap between
// samples, and whether the probe container itself ever restarted.
type EvalParams struct {
	ProbeStart      time.Time
	TransitionStart time.Time
	TransitionEnd   time.Time
	MeasurementEnd  time.Time

	ExpectedProbes []string
	Interval       time.Duration
	MaxGap         time.Duration

	ContainerRestarted bool

	// MinSamplesPerWindow is the minimum recorded samples required per probe kind in the baseline
	// and post windows (not the transition window, whose duration depends on how long the Helm
	// upgrade actually took and so gets no fixed sample-count floor).
	MinSamplesPerWindow int

	// ParseErrors carries any log lines that failed to parse as a Record before reaching Evaluate
	// (malformed JSON) — surfaced as a failure rather than silently dropped.
	ParseErrors []string
}

// windowCounts holds one probe kind's sample/failure counts within one window.
type windowCounts struct {
	total int
	fail  int
}

// ProbeSummary is one probe kind's evaluated result.
type ProbeSummary struct {
	Kind       string
	Baseline   windowCounts
	Transition windowCounts
	Post       windowCounts
	MaxGap     time.Duration
}

func (s ProbeSummary) failures() int {
	return s.Baseline.fail + s.Transition.fail + s.Post.fail
}

// Result is the evaluator's typed verdict over one measured run.
type Result struct {
	Summaries          []ProbeSummary
	MissingProbes      []string
	ContainerRestarted bool
	MaxGap             time.Duration
	MaxGapLimit        time.Duration
	MinSamplesRequired int
	ParseErrors        []string
}

// Passed reports whether the run satisfies the availability contract: zero recorded failures,
// every expected probe kind present with enough baseline/post samples, no gap exceeding the
// configured limit, no probe container restart, and no unparseable log line.
func (r Result) Passed() bool {
	return len(r.FailureReasons()) == 0
}

// FailureReasons reports every detected problem, not just the first — so a failing run is
// diagnosable from a single report instead of a bisection across re-runs.
func (r Result) FailureReasons() []string {
	var reasons []string
	if len(r.ParseErrors) > 0 {
		reasons = append(reasons, fmt.Sprintf("%d probe log line(s) failed to parse: %s", len(r.ParseErrors), strings.Join(r.ParseErrors, "; ")))
	}
	if r.ContainerRestarted {
		reasons = append(reasons, "the availability probe container restarted during the run")
	}
	for _, missing := range r.MissingProbes {
		reasons = append(reasons, fmt.Sprintf("probe kind %q never produced a single sample", missing))
	}
	for _, s := range r.Summaries {
		if s.Baseline.total < r.MinSamplesRequired {
			reasons = append(reasons, fmt.Sprintf("probe=%s: insufficient baseline samples (%d < %d)", s.Kind, s.Baseline.total, r.MinSamplesRequired))
		}
		if s.Post.total < r.MinSamplesRequired {
			reasons = append(reasons, fmt.Sprintf("probe=%s: insufficient post-transition samples (%d < %d)", s.Kind, s.Post.total, r.MinSamplesRequired))
		}
		if s.failures() > 0 {
			reasons = append(reasons, fmt.Sprintf("probe=%s: %d failed sample(s) recorded (baseline=%d transition=%d post=%d)",
				s.Kind, s.failures(), s.Baseline.fail, s.Transition.fail, s.Post.fail))
		}
		if s.MaxGap > r.MaxGapLimit {
			reasons = append(reasons, fmt.Sprintf("probe=%s: gap of %s exceeds the %s limit", s.Kind, s.MaxGap, r.MaxGapLimit))
		}
	}
	return reasons
}

// Evaluate applies the pass/fail rules to a captured run: strict zero-failure acceptance, a
// bounded gap between samples and the measurement boundaries, and a minimum sample count in the
// fixed-duration baseline and post-transition windows. The transition window gets no sample-count
// floor — its duration is whatever the Helm upgrade actually took, and a fast rollout might not
// contain a single sample at a one-second interval.
func Evaluate(records []Record, params EvalParams) Result {
	result := Result{
		ContainerRestarted: params.ContainerRestarted,
		MaxGapLimit:        params.MaxGap,
		MinSamplesRequired: params.MinSamplesPerWindow,
		ParseErrors:        params.ParseErrors,
	}

	byKind := make(map[string][]Record)
	for _, rec := range records {
		byKind[rec.Probe] = append(byKind[rec.Probe], rec)
	}

	for _, kind := range params.ExpectedProbes {
		recs := byKind[kind]
		if len(recs) == 0 {
			result.MissingProbes = append(result.MissingProbes, kind)
			continue
		}
		result.Summaries = append(result.Summaries, summarize(kind, recs, params))
	}
	sort.Strings(result.MissingProbes)

	return result
}

func summarize(kind string, recs []Record, params EvalParams) ProbeSummary {
	s := ProbeSummary{Kind: kind}

	var timestamps []time.Time
	for _, rec := range recs {
		ts, err := time.Parse(TimestampLayout, rec.Timestamp)
		if err != nil {
			continue
		}
		switch {
		case !ts.Before(params.ProbeStart) && ts.Before(params.TransitionStart):
			s.Baseline.total++
			if !rec.Success {
				s.Baseline.fail++
			}
		case !ts.Before(params.TransitionStart) && !ts.After(params.TransitionEnd):
			s.Transition.total++
			if !rec.Success {
				s.Transition.fail++
			}
		case ts.After(params.TransitionEnd) && !ts.After(params.MeasurementEnd):
			s.Post.total++
			if !rec.Success {
				s.Post.fail++
			}
		}
		// Bounded to the full measured window: a sample recorded outside [probeStart,
		// measurementEnd] while the logs are being collected is fixture activity,
		// not part of what this evaluator measures, and must not distort the gap calculation.
		if !ts.Before(params.ProbeStart) && !ts.After(params.MeasurementEnd) {
			timestamps = append(timestamps, ts)
		}
	}

	s.MaxGap = maxGap(timestamps, params.ProbeStart, params.MeasurementEnd)
	return s
}

// maxGap returns the largest interval between consecutive samples, including the boundary
// intervals from probeStart to the first sample and from the last sample to measurementEnd — a
// probe that starts late or stops early cannot pass.
func maxGap(timestamps []time.Time, probeStart, measurementEnd time.Time) time.Duration {
	sort.Slice(timestamps, func(i, j int) bool { return timestamps[i].Before(timestamps[j]) })
	all := make([]time.Time, 0, len(timestamps)+2)
	all = append(all, probeStart)
	all = append(all, timestamps...)
	all = append(all, measurementEnd)

	var max time.Duration
	for i := 1; i < len(all); i++ {
		if gap := all[i].Sub(all[i-1]); gap > max {
			max = gap
		}
	}
	return max
}

// Summary renders a human-readable verdict: PASS with the per-probe sample counts, or FAIL with
// every detected failure reason — the textual report the evaluate CLI mode prints.
func (r Result) Summary() string {
	if r.Passed() {
		var lines []string
		for _, s := range r.Summaries {
			lines = append(lines, fmt.Sprintf("  probe=%s baseline=%d transition=%d post=%d maxGap=%s",
				s.Kind, s.Baseline.total, s.Transition.total, s.Post.total, s.MaxGap))
		}
		return "PASS: availability contract satisfied\n" + strings.Join(lines, "\n")
	}
	return "FAIL: availability contract violated:\n  - " + strings.Join(r.FailureReasons(), "\n  - ")
}
