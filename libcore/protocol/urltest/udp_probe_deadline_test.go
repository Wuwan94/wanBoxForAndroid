// SPDX-License-Identifier: GPL-3.0-or-later
package urltest

import (
	"context"
	"testing"
	"time"
)

// A deadline is authoritative even when its timer has not published cancellation yet.
type udpProbeDeadlineContext struct {
	context.Context
	deadline time.Time
}

func (c udpProbeDeadlineContext) Deadline() (time.Time, bool) { return c.deadline, true }

func TestUDPProbeDeadlineClassification(t *testing.T) {
	parent := context.Background()
	past := udpProbeDeadlineContext{parent, time.Now().Add(-time.Hour)}
	future := udpProbeDeadlineContext{parent, time.Now().Add(time.Hour)}
	canceled, cancel := context.WithCancel(parent)
	cancel()
	for _, tc := range []struct {
		name          string
		parent, probe context.Context
		stopped       bool
		outcome       UDPProbeOutcome
	}{
		{"in budget", parent, future, false, UDPProbeSkipped},
		{"probe budget elapsed", parent, past, true, UDPProbeUnverified},
		{"round budget elapsed", past, past, true, UDPProbeCanceled},
		{"parent canceled", canceled, past, true, UDPProbeCanceled},
	} {
		t.Run(tc.name, func(t *testing.T) {
			outcome, stopped := udpProbeContextOutcome(tc.parent, tc.probe)
			if outcome != tc.outcome || stopped != tc.stopped {
				t.Fatalf("got (%v, %v), want (%v, %v)", outcome, stopped, tc.outcome, tc.stopped)
			}
		})
	}
	if !udpProbeContextStopped(future, future.deadline) {
		t.Fatal("the exact deadline is outside the probe budget")
	}
}
