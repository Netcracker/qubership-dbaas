package probe

import (
	"encoding/json"
	"fmt"
	"sort"
	"strings"
	"time"
)

// ParseRecords returns valid JSONL records and reports each invalid line.
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

// EvalParams defines the measured windows and availability limits.
type EvalParams struct {
	ProbeStart      time.Time
	TransitionStart time.Time
	TransitionEnd   time.Time
	MeasurementEnd  time.Time

	ExpectedProbes []string
	Interval       time.Duration
	MaxGap         time.Duration

	ContainerRestarted bool

	// MinSamplesPerWindow applies to the baseline and post-transition windows.
	MinSamplesPerWindow int

	// ParseErrors contains log lines that could not be parsed as records.
	ParseErrors []string
}

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

// Passed reports whether the run satisfies the availability contract.
func (r Result) Passed() bool {
	return len(r.FailureReasons()) == 0
}

// FailureReasons reports every availability contract violation.
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

// Evaluate applies the availability contract to the captured probe records.
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
		// Ignore samples emitted before measurement starts or while logs are collected.
		if !ts.Before(params.ProbeStart) && !ts.After(params.MeasurementEnd) {
			timestamps = append(timestamps, ts)
		}
	}

	s.MaxGap = maxGap(timestamps, params.ProbeStart, params.MeasurementEnd)
	return s
}

// maxGap includes the intervals between each measurement boundary and its nearest sample.
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

// Summary renders the availability verdict and probe counts.
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
