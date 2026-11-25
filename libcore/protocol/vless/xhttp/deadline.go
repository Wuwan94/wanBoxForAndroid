package xhttp

import (
	"net"
	"os"
	"sync"
	"time"
)

// connDeadline publishes a deadline and a generation channel. A setter closes
// the old generation so every pending operation can recalculate its timer.
type connDeadline struct {
	mu       sync.Mutex
	deadline time.Time
	changed  chan struct{}
	fired    bool
}

func (d *connDeadline) snapshot() (time.Time, <-chan struct{}) {
	d.mu.Lock()
	defer d.mu.Unlock()
	if d.changed == nil {
		d.changed = make(chan struct{})
	}
	return d.deadline, d.changed
}

func (d *connDeadline) set(deadline time.Time) {
	d.mu.Lock()
	if d.changed == nil {
		d.changed = make(chan struct{})
	} else {
		close(d.changed)
		d.changed = make(chan struct{})
	}
	d.deadline = deadline
	d.fired = false
	d.mu.Unlock()
}

func (d *connDeadline) expired(now time.Time) bool {
	deadline, _ := d.snapshot()
	return !deadline.IsZero() && !now.Before(deadline)
}

// claimExpired serializes timeout delivery with concurrent deadline updates.
func (d *connDeadline) claimExpired(now time.Time) bool {
	d.mu.Lock()
	defer d.mu.Unlock()
	if d.deadline.IsZero() || now.Before(d.deadline) || d.fired {
		return false
	}
	d.fired = true
	return true
}

type connIOResult struct {
	n   int
	err error
}

func interruptedConnIOResult(deadline *connDeadline, result connIOResult) (int, error) {
	if deadline.expired(time.Now()) {
		return result.n, os.ErrDeadlineExceeded
	}
	return result.n, result.err
}

func closedConnIOError(deadline *connDeadline) error {
	if deadline.expired(time.Now()) {
		return os.ErrDeadlineExceeded
	}
	return net.ErrClosed
}

func stopConnTimer(timer *time.Timer) {
	if timer == nil {
		return
	}
	if !timer.Stop() {
		select {
		case <-timer.C:
		default:
		}
	}
}

func connTimer(deadline time.Time) (*time.Timer, <-chan time.Time) {
	if deadline.IsZero() {
		return nil, nil
	}
	timer := time.NewTimer(time.Until(deadline))
	return timer, timer.C
}

func (c *splitConn) acquireIOGate(deadline *connDeadline, gate chan struct{}) (func(), error) {
	for {
		if c.closed.Load() {
			return nil, closedConnIOError(deadline)
		}
		until, changed := deadline.snapshot()
		if !until.IsZero() && !time.Now().Before(until) {
			return nil, os.ErrDeadlineExceeded
		}
		timer, timerC := connTimer(until)
		select {
		case <-gate:
			stopConnTimer(timer)
			if c.closed.Load() {
				gate <- struct{}{}
				return nil, closedConnIOError(deadline)
			}
			return func() { gate <- struct{}{} }, nil
		case <-c.lifeDone:
			stopConnTimer(timer)
			return nil, closedConnIOError(deadline)
		case <-changed:
			stopConnTimer(timer)
		case <-timerC:
			stopConnTimer(timer)
		}
	}
}

// runIO runs the blocking primitive in one owned worker. XHTTP exposes one-shot
// HTTP bodies and pipes rather than deadline-capable sockets, so the timeout
// action is supplied by the caller: reads close the whole session because the
// server drops the session when its downlink ends; writes close only the upload
// pipe so an independent downlink can remain usable. Neither direction can be
// reopened after its underlying request/pipe has been closed.
func (c *splitConn) runIO(deadline *connDeadline, gate chan struct{}, interrupt func() error, operation func() (int, error)) (int, error) {
	c.init()
	release, err := c.acquireIOGate(deadline, gate)
	if err != nil {
		return 0, err
	}
	defer release()
	if c.closed.Load() {
		return 0, closedConnIOError(deadline)
	}
	if deadline.expired(time.Now()) {
		return 0, os.ErrDeadlineExceeded
	}

	result := make(chan connIOResult, 1)
	go func() {
		n, err := operation()
		result <- connIOResult{n: n, err: err}
	}()

	for {
		if c.closed.Load() {
			res := <-result
			return interruptedConnIOResult(deadline, res)
		}
		until, changed := deadline.snapshot()
		if !until.IsZero() && !time.Now().Before(until) {
			if deadline.claimExpired(time.Now()) {
				_ = interrupt()
				res := <-result
				return res.n, os.ErrDeadlineExceeded
			}
			continue
		}

		timer, timerC := connTimer(until)
		select {
		case res := <-result:
			stopConnTimer(timer)
			return res.n, res.err
		case <-c.lifeDone:
			stopConnTimer(timer)
			res := <-result
			return interruptedConnIOResult(deadline, res)
		case <-changed:
			stopConnTimer(timer)
		case <-timerC:
			stopConnTimer(timer)
			if c.closed.Load() {
				res := <-result
				return interruptedConnIOResult(deadline, res)
			}
			if deadline.claimExpired(time.Now()) {
				_ = interrupt()
				res := <-result
				return res.n, os.ErrDeadlineExceeded
			}
		}
	}
}
