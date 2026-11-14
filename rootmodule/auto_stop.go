package module

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"io"
	"net"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"sort"
	"strconv"
	"strings"
	"time"
)

const autoStopFile = "smart_auto_stop.json"

var androidPackage = regexp.MustCompile(`^[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*)+$`)

type SmartAutoStop struct {
	Enabled    bool     `json:"enabled"`
	Rules      []string `json:"rules"`
	AppPackage string   `json:"appPackage"`
	UserID     *int     `json:"userId"`
}
type NetworkObservation struct {
	Interfaces map[string][]net.IP
	Gateways   map[string][]net.IP
}
type AutoStopRule struct {
	Gateway bool
	IP      net.IP
	Network *net.IPNet
}

func CheckSmartAutoStop(b []byte) (SmartAutoStop, error) {
	var s SmartAutoStop
	if len(b) == 0 {
		return s, nil
	}
	if json.Unmarshal(b, &s) != nil {
		return s, errors.New("auto_stop_invalid")
	}
	if s.UserID != nil && (*s.UserID < 0 || *s.UserID > 21474) {
		return s, errors.New("auto_stop_user_invalid")
	}
	if s.Enabled && s.UserID == nil {
		return s, errors.New("auto_stop_user_required")
	}
	if s.Rules == nil {
		s.Rules = []string{}
	}
	if !s.Enabled && len(s.Rules) == 0 && s.AppPackage == "" {
		return s, nil
	}
	if s.AppPackage != "" && !androidPackage.MatchString(s.AppPackage) {
		return s, errors.New("auto_stop_package_invalid")
	}
	out := []string{}
	for _, r := range splitAutoRules(strings.Join(s.Rules, "\n")) {
		if r != "" {
			out = append(out, r)
		}
	}
	if len(out) > 5 {
		return s, errors.New("auto_stop_too_many_rules")
	}
	s.Rules = out
	if s.Enabled && s.AppPackage == "" {
		return s, errors.New("auto_stop_package_required")
	}
	for _, r := range out {
		if _, e := parseAutoRule(r); e != nil {
			return s, e
		}
	}
	return s, nil
}
func splitAutoRules(v string) []string {
	return strings.FieldsFunc(v, func(r rune) bool { return r == ',' || r == ';' || r == '，' || r == '\n' || r == '\r' })
}
func parseAutoRule(v string) (AutoStopRule, error) {
	v = strings.TrimSpace(v)
	gateway := false
	if len(v) >= 8 && strings.EqualFold(v[:8], "gateway:") {
		gateway = true
		v = strings.TrimSpace(v[8:])
	}
	if strings.Contains(v, "/") {
		ip, n, e := net.ParseCIDR(v)
		if e != nil || ip == nil {
			return AutoStopRule{}, errors.New("auto_stop_rule_invalid")
		}
		n.IP = ip
		return AutoStopRule{Gateway: gateway, Network: n}, nil
	}
	ip := net.ParseIP(v)
	if ip == nil {
		return AutoStopRule{}, errors.New("auto_stop_rule_invalid")
	}
	return AutoStopRule{Gateway: gateway, IP: ip}, nil
}
func AutoStopMatches(s SmartAutoStop, o NetworkObservation) bool {
	if !s.Enabled {
		return false
	}
	for _, raw := range s.Rules {
		r, e := parseAutoRule(raw)
		if e != nil {
			continue
		}
		var xs []net.IP
		for name, addresses := range o.Interfaces {
			if ignoredInterface(name) || len(addresses) == 0 {
				continue
			}
			if r.Gateway {
				xs = append(xs, o.Gateways[name]...)
			} else {
				xs = append(xs, addresses...)
			}
		}
		for _, ip := range xs {
			if r.IP != nil && r.IP.Equal(ip) || (r.Network != nil && r.Network.Contains(ip)) {
				return true
			}
		}
	}
	return false
}

func AutoStopSuppressed(current, resumed string) bool { return resumed != "" && current == resumed }
func NetworkFingerprint(o NetworkObservation) string {
	var p []string
	unique := map[string]bool{}
	for n, ips := range o.Interfaces {
		if ignoredInterface(n) || len(ips) == 0 {
			continue
		}
		for _, ip := range ips {
			unique[n+"="+ip.String()] = true
		}
		for _, ip := range o.Gateways[n] {
			unique[n+"=g="+ip.String()] = true
		}
	}
	for entry := range unique {
		p = append(p, entry)
	}
	sort.Strings(p)
	h := sha256.Sum256([]byte(strings.Join(p, "\n")))
	return hex.EncodeToString(h[:8])
}
func ReadPhysicalObservation() NetworkObservation {
	o := NetworkObservation{Interfaces: map[string][]net.IP{}}
	for _, in := range interfacesOrEmpty() {
		if in.Flags&net.FlagUp == 0 || in.Flags&net.FlagLoopback != 0 || ignoredInterface(in.Name) {
			continue
		}
		as, _ := in.Addrs()
		addresses := make([]string, 0, len(as))
		for _, a := range as {
			addresses = append(addresses, a.String())
		}
		if ips := physicalUnicastAddresses(addresses); len(ips) > 0 {
			o.Interfaces[in.Name] = ips
		}
	}
	o.Gateways = readDefaultGateways()
	return o
}

// physicalUnicastAddresses retains link-local addresses on active physical interfaces.
func physicalUnicastAddresses(addresses []string) []net.IP {
	var ips []net.IP
	for _, address := range addresses {
		ip, _, _ := net.ParseCIDR(address)
		if ip != nil && !ip.IsLoopback() && !ip.IsUnspecified() && !ip.IsMulticast() {
			ips = append(ips, ip)
		}
	}
	return ips
}

func readDefaultGateways() map[string][]net.IP {
	v4, _ := os.ReadFile("/proc/net/route")
	v6, _ := os.ReadFile("/proc/net/ipv6_route")
	out := parseDefaultGateways(v4, v6)
	// Android keeps per-network defaults in policy tables, outside /proc/net/route.
	// Use fixed argv without a shell, and retain proc observations on command failure.
	for _, family := range []string{"-4", "-6"} {
		ctx, cancel := context.WithTimeout(context.Background(), 500*time.Millisecond)
		data, err := exec.CommandContext(ctx, "ip", family, "route", "show", "table", "all").Output()
		cancel()
		if err != nil {
			continue
		}
		for name, gateways := range parseIPDefaultGateways(data) {
			out[name] = append(out[name], gateways...)
		}
	}
	return out
}

// Linux exposes IPv4 gateways as little-endian words and IPv6 as network-order hex.
func parseDefaultGateways(v4, v6 []byte) map[string][]net.IP {
	out := map[string][]net.IP{}
	for _, line := range strings.Split(string(v4), "\n") {
		f := strings.Fields(line)
		if len(f) < 8 || f[1] != "00000000" || f[7] != "00000000" {
			continue
		}
		flags, err := strconv.ParseUint(f[3], 16, 32)
		if err != nil || flags&1 == 0 {
			continue
		}
		v, err := strconv.ParseUint(f[2], 16, 32)
		if err == nil && v != 0 {
			out[f[0]] = append(out[f[0]], net.IPv4(byte(v), byte(v>>8), byte(v>>16), byte(v>>24)))
		}
	}
	for _, line := range strings.Split(string(v6), "\n") {
		f := strings.Fields(line)
		if len(f) != 10 || f[0] != strings.Repeat("0", 32) || f[1] != "00" {
			continue
		}
		flags, err := strconv.ParseUint(f[8], 16, 32)
		if err != nil || flags&1 == 0 {
			continue
		}
		raw, err := hex.DecodeString(f[4])
		if err == nil && len(raw) == net.IPv6len && !net.IP(raw).IsUnspecified() {
			out[f[9]] = append(out[f[9]], net.IP(raw))
		}
	}
	return out
}
func interfacesOrEmpty() []net.Interface { x, _ := net.Interfaces(); return x }
func BuildAutoStopBroadcast(pkg, phase string, userID int) ([]string, error) {
	if userID < 0 || userID > 21474 || !androidPackage.MatchString(pkg) || !map[string]bool{"suspended": true, "connected": true, "stopped": true, "failed": true, "disabled": true}[phase] {
		return nil, errors.New("broadcast_invalid")
	}
	return []string{"/system/bin/am", "broadcast", "--user", strconv.Itoa(userID), "-n", pkg + "/io.nekohasekai.sagernet.bg.SmartAutoStopReceiver", "-a", pkg + ".AUTO_STOP_STATE", "--es", "phase", phase}, nil
}
func readAutoStop(path string) (SmartAutoStop, error) {
	b, e := os.ReadFile(path)
	if os.IsNotExist(e) {
		return SmartAutoStop{}, nil
	}
	if e != nil {
		return SmartAutoStop{}, e
	}
	return CheckSmartAutoStop(b)
}

const autoStopBroadcastTimeout = time.Second

var runAutoStopCommand = func(args ...string) error {
	ctx, cancel := context.WithTimeout(context.Background(), autoStopBroadcastTimeout)
	defer cancel()
	cmd := exec.CommandContext(ctx, args[0], args[1:]...)
	cmd.WaitDelay = 100 * time.Millisecond
	return cmd.Run()
}

type autoStopNotification struct {
	Policy SmartAutoStop `json:"policy"`
	Phase  string        `json:"phase"`
}

// Persist the latest phase and detach delivery from the supervisor's lifetime.
// No broadcast command is awaited on a networking lifecycle path.
func (r *Runtime) QueueAutoStopBroadcast(policy SmartAutoStop, phase string) {
	if policy.UserID == nil {
		return
	}
	if _, err := BuildAutoStopBroadcast(policy.AppPackage, phase, *policy.UserID); err != nil {
		return
	}
	if err := jsonWrite(r.path("runtime", "notification.json"), autoStopNotification{policy, phase}); err != nil {
		return
	}
	cmd := exec.Command(filepath.Join(r.ModuleDir, "bin", "wanboxctl"), "__internal", "auto-stop-notify")
	detach(cmd)
	if cmd.Start() == nil {
		go cmd.Wait()
	}
}

// DispatchAutoStopBroadcasts serializes delivery across supervisor replacements.
// Queued workers coalesce to the latest persisted phase, including on shutdown.
func (r *Runtime) DispatchAutoStopBroadcasts(ctx context.Context) error {
	unlock, err := fileLock(r.path("runtime", "notification.lock"))
	if err != nil {
		return err
	}
	defer unlock()
	failures := 0
	for {
		if err := ctx.Err(); err != nil {
			return err
		}
		data, err := os.ReadFile(r.path("runtime", "notification.json"))
		if err != nil {
			return err
		}
		delivered, _ := os.ReadFile(r.path("runtime", "notification-delivered.json"))
		if bytes.Equal(data, delivered) {
			return nil
		}
		var notification autoStopNotification
		if json.Unmarshal(data, &notification) != nil || notification.Policy.UserID == nil {
			return errors.New("broadcast_invalid")
		}
		args, err := BuildAutoStopBroadcast(notification.Policy.AppPackage, notification.Phase, *notification.Policy.UserID)
		if err != nil {
			return err
		}
		runCommand := r.autoStopCommand
		if runCommand == nil {
			runCommand = runAutoStopCommand
		}
		if err := runCommand(args...); err != nil {
			failures++
			if failures >= 3 {
				return err
			}
			timer := time.NewTimer(10 * time.Millisecond)
			select {
			case <-ctx.Done():
				timer.Stop()
				return ctx.Err()
			case <-timer.C:
			}
			// Reread the persisted queue: a newer phase supersedes this failure.
			continue
		}
		failures = 0
		// Only successful delivery is acknowledged; exhausted failures stay pending.
		if err := AtomicWrite(r.path("runtime", "notification-delivered.json"), data, 0600); err != nil {
			return err
		}
	}
}

// autoStopMonitorFingerprint evaluates one observation and consumes manual suppression
// permanently on the first network change, even if the old network later returns.
type networkDecision struct {
	Fingerprint string
	Suspend     bool
}

func autoStopMonitorFingerprint(policy SmartAutoStop, o NetworkObservation, suppressed *string) networkDecision {
	fp := NetworkFingerprint(o)
	if *suppressed != "" && *suppressed != fp {
		*suppressed = ""
	}
	if AutoStopMatches(policy, o) && !AutoStopSuppressed(fp, *suppressed) {
		return networkDecision{Fingerprint: fp, Suspend: true}
	}
	return networkDecision{Fingerprint: fp}
}

func (r *Runtime) physicalObservation() NetworkObservation {
	if r.observeNetwork != nil {
		return r.observeNetwork()
	}
	return ReadPhysicalObservation()
}

// parseIPDefaultGateways reads iproute2/toybox output, including Android policy tables.
func parseIPDefaultGateways(data []byte) map[string][]net.IP {
	out := map[string][]net.IP{}
	for _, line := range strings.Split(string(data), "\n") {
		fields := strings.Fields(line)
		if len(fields) == 0 || fields[0] != "default" {
			continue
		}
		var gateway net.IP
		var device string
		valid := true
		for i := 1; i < len(fields); i++ {
			switch fields[i] {
			case "via":
				if gateway != nil || i+1 >= len(fields) {
					valid = false
					break
				}
				i++
				gateway = net.ParseIP(fields[i])
				if gateway == nil || gateway.IsUnspecified() || gateway.IsMulticast() {
					valid = false
				}
			case "dev":
				if device != "" || i+1 >= len(fields) {
					valid = false
					break
				}
				i++
				device = fields[i]
				if len(device) > 15 || !routeInterface.MatchString(device) {
					valid = false
				}
			}
		}
		if valid && gateway != nil && device != "" {
			out[device] = append(out[device], gateway)
		}
	}
	return out
}

var routeInterface = regexp.MustCompile(`^[a-zA-Z0-9_.-]+$`)

const suppressionFile = "auto_stop_suppression"

func (r *Runtime) readSuppression() string {
	f, err := os.Open(r.path("runtime", suppressionFile))
	if err != nil {
		return ""
	}
	defer f.Close()
	b, err := io.ReadAll(io.LimitReader(f, 129))
	if err != nil || len(b) != 16 {
		return ""
	}
	if _, err := hex.DecodeString(string(b)); err != nil {
		return ""
	}
	return string(b)
}

// Both lifecycle loops consume suppression through the same network decision.
func (r *Runtime) autoStopDecision(policy SmartAutoStop, o NetworkObservation, suppressed *string) networkDecision {
	before := *suppressed
	decision := autoStopMonitorFingerprint(policy, o, suppressed)
	if before != "" && *suppressed == "" {
		_ = os.Remove(r.path("runtime", suppressionFile))
	}
	return decision
}
