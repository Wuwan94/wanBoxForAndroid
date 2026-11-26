// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package module

import (
	"context"
	"errors"
	"fmt"
	"io"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strconv"
	"strings"
	"time"
)

const googleFirewallRepairTimeout = 15 * time.Second

var errGoogleFirewallModuleInactive = errors.New("module_inactive")

func googleFirewallMutationGuard(ctx context.Context, moduleDir string) error {
	if err := ctx.Err(); err != nil {
		return err
	}
	if !googleFirewallModuleActive(moduleDir) {
		return errGoogleFirewallModuleInactive
	}
	return nil
}

func googleFirewallModuleActive(dir string) bool {
	if dir == "" {
		return false
	}
	prop, err := os.Lstat(filepath.Join(dir, "module.prop"))
	if err != nil || !prop.Mode().IsRegular() || prop.Mode()&os.ModeSymlink != 0 {
		return false
	}
	for _, marker := range []string{"disable", "remove"} {
		if _, err := os.Lstat(filepath.Join(dir, marker)); err == nil || !errors.Is(err, os.ErrNotExist) {
			return false
		}
	}
	cli, err := os.Lstat(filepath.Join(dir, "bin", "wanboxctl"))
	return err == nil && cli.Mode().IsRegular() && cli.Mode()&os.ModeSymlink == 0 && cli.Mode().Perm()&0111 != 0
}

// scheduleGoogleFirewallRepair is deliberately best-effort: neither boot nor a
// committed module update may be failed by this optional compatibility repair.
func (r *Runtime) scheduleGoogleFirewallRepair(moduleDir string, delay time.Duration) {
	if r.launchGoogleFirewallRepair == nil {
		return
	}
	if err := r.launchGoogleFirewallRepair(moduleDir, delay); err != nil {
		r.recordEventLimited("google_firewall_schedule_failed", time.Minute)
	}
}

// launchGoogleFirewallRepairProcess starts the fixed internal CLI command in a
// new session. The repair must outlive the short boot/update caller, but it is
// never a shell command and receives no App-controlled arguments.
func launchGoogleFirewallRepairProcess(moduleDir string, delay time.Duration) error {
	if moduleDir == "" || delay < 0 || delay > 5*time.Minute || delay%time.Second != 0 {
		return errors.New("arguments_invalid")
	}
	binary := filepath.Join(moduleDir, "bin", "wanboxctl")
	info, err := os.Lstat(binary)
	if err != nil || !info.Mode().IsRegular() {
		return errors.New("google_firewall_cli_unavailable")
	}
	args := []string{"__internal", "google-firewall"}
	if delay > 0 {
		args = append(args, "--delay-seconds", strconv.FormatInt(int64(delay/time.Second), 10))
	}
	cmd := exec.Command(binary, args...)
	detach(cmd)
	cmd.Stdin = nil
	cmd.Stdout = io.Discard
	cmd.Stderr = io.Discard
	if err := cmd.Start(); err != nil {
		return errors.New("google_firewall_start_failed")
	}
	go func() { _ = cmd.Wait() }()
	return nil
}

// RepairGoogleConnectivity is invoked only by the root-only fixed CLI command.
// Unknown brands, ROMs, policy states, users and BPF layouts are no-ops.
func (r *Runtime) RepairGoogleConnectivity(ctx context.Context, delay time.Duration) error {
	if runtime.GOOS != "android" || !googleFirewallModuleActive(r.ModuleDir) {
		return nil
	}
	if delay < 0 || delay > 5*time.Minute {
		return errors.New("arguments_invalid")
	}
	ctx, cancel := context.WithTimeout(ctx, delay+googleFirewallRepairTimeout)
	defer cancel()

	probe, cancelProbe := context.WithTimeout(ctx, 3*time.Second)
	brandOutput, brandErr := exec.CommandContext(probe, "/system/bin/getprop", "ro.product.brand").Output()
	romOutput, romErr := exec.CommandContext(probe, "/system/bin/getprop", "ro.build.version.oplusrom").Output()
	cancelProbe()
	if err := ctx.Err(); err != nil {
		return err
	}
	if brandErr != nil {
		return nil
	}
	brand := strings.TrimSpace(string(brandOutput))
	colorOS := romErr == nil && colorOS16GoogleFirewall(brand, string(romOutput))
	if !colorOS && !googleFirewallBrand(brand) {
		return nil
	}

	if delay > 0 {
		timer := time.NewTimer(delay)
		defer timer.Stop()
		select {
		case <-ctx.Done():
			return ctx.Err()
		case <-timer.C:
		}
	}
	// The user or module manager may disable/remove the module during the delay.
	if ctx.Err() != nil || !googleFirewallModuleActive(r.ModuleDir) {
		return ctx.Err()
	}

	packages := []string{"com.google.android.gms", "com.android.vending"}
	if colorOS {
		packages = append(packages, "com.google.android.configupdater")
	}
	listCtx, cancelList := context.WithTimeout(ctx, 8*time.Second)
	output, err := exec.CommandContext(listCtx, "/system/bin/cmd", "package", "list", "packages", "--user", "0", "-U").Output()
	cancelList()
	if err != nil {
		r.recordEventLimited("google_firewall_uid_resolution_failed", time.Minute)
		return errors.New("google_package_list_unavailable")
	}
	packageUIDs, err := parseGooglePackageUIDs(string(output))
	if err != nil {
		r.recordEventLimited("google_firewall_uid_resolution_failed", time.Minute)
		return err
	}
	uids := exclusiveGooglePackageUIDs(packageUIDs, packages)
	if len(uids) == 0 {
		return nil
	}
	beforeMutation := func() error { return googleFirewallMutationGuard(ctx, r.ModuleDir) }

	var removed int
	if colorOS {
		if !canRepairColorOSGoogle(ctx) {
			return nil
		}
		removed, err = repairColorOSGoogleFirewall(uids, beforeMutation)
	} else {
		removed, err = repairGoogleFirewallWithGuard(uids, func(tool string, args ...string) ([]byte, error) {
			commandCtx, commandCancel := context.WithTimeout(ctx, 3*time.Second)
			defer commandCancel()
			return exec.CommandContext(commandCtx, filepath.Join("/system/bin", tool), args...).Output()
		}, beforeMutation)
	}
	if errors.Is(err, errGoogleFirewallModuleInactive) {
		return nil
	}
	if err != nil {
		r.recordEventLimited("google_firewall_repair_failed", time.Minute)
		return fmt.Errorf("google_firewall_repair_failed: %w", err)
	}
	if removed > 0 {
		r.recordEvent("google_firewall_repaired:" + strconv.Itoa(removed))
	}
	return nil
}

func canRepairColorOSGoogle(ctx context.Context) bool {
	userCtx, cancel := context.WithTimeout(ctx, 6*time.Second)
	defer cancel()
	currentUser, err := exec.CommandContext(userCtx, "/system/bin/am", "get-current-user").Output()
	if err != nil {
		return false
	}
	secure, err := exec.CommandContext(userCtx, "/system/bin/settings", "--user", "0", "get", "secure", "google_restric_info").Output()
	if err != nil {
		return false
	}
	manual, err := exec.CommandContext(userCtx, "/system/bin/settings", "get", "global", "oplus_user_change_gms_network_control").Output()
	if err != nil {
		return false
	}
	traffic, err := exec.CommandContext(userCtx, "/system/bin/settings", "get", "global", "oplus_comm_trafficmonitor_gms_network_control").Output()
	if err != nil {
		return false
	}
	return colorOSGoogleRepairAllowed(string(currentUser), string(secure), string(manual), string(traffic))
}

// ParseGoogleFirewallDelayArgs accepts only the fixed internal CLI grammar.
func ParseGoogleFirewallDelayArgs(args []string) (time.Duration, error) {
	if len(args) == 0 {
		return 0, nil
	}
	if len(args) != 2 || args[0] != "--delay-seconds" {
		return 0, errors.New("arguments_invalid")
	}
	seconds, err := strconv.ParseInt(args[1], 10, 64)
	if err != nil || seconds < 0 || seconds > int64((5*time.Minute)/time.Second) || strconv.FormatInt(seconds, 10) != args[1] {
		return 0, errors.New("arguments_invalid")
	}
	return time.Duration(seconds) * time.Second, nil
}
