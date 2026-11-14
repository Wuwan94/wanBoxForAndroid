package libcore

import (
	"errors"
	"testing"
)

func TestRootCloseFailureIsSafe(t *testing.T) {
	err := rootCloseResult(nil, func() error { return errors.New("secret raw core output") })
	if err == nil || err.Error() != "core_cleanup_failed" {
		t.Fatalf("unsafe close result: %v", err)
	}
	if err := rootCloseResult(nil, func() error { return nil }); err != nil {
		t.Fatal(err)
	}
}

func TestRootCloseRetainsDiagnosticsAtSafeBoundary(t *testing.T) {
	startup := errors.New("startup detail")
	cleanup := errors.New("cleanup detail")
	err := rootCloseResult(startup, func() error { return cleanup })
	if err == nil || err.Error() != "core_cleanup_failed" || !errors.Is(err, startup) || !errors.Is(err, cleanup) {
		t.Fatalf("lost controlled diagnostics: %v", err)
	}
}
