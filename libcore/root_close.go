package libcore

import "errors"

// Close failures must fail the standalone process without exposing core diagnostics.
func rootCloseResult(result error, close func() error) error {
	if err := close(); err != nil {
		return rootCleanupError{errors.Join(result, err)}
	}
	return result
}

// Retain diagnostics for controlled callers while keeping process-facing text fixed.
type rootCleanupError struct{ cause error }

func (rootCleanupError) Error() string   { return "core_cleanup_failed" }
func (e rootCleanupError) Unwrap() error { return e.cause }
