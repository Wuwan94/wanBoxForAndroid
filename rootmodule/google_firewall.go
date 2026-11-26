// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package module

import (
	"bufio"
	"errors"
	"fmt"
	"os"
	"strconv"
	"strings"
)

const googleFirewallChain = "zte_fw_gms"

// repairGoogleFirewall removes only exact single-UID owner blocks from the
// vendor's dedicated GMS chain. It never flushes chains or edits shared/DNS
// rules; command execution is injected so the mutation boundary is testable.
func repairGoogleFirewall(uids map[uint32]bool, run func(string, ...string) ([]byte, error)) (int, error) {
	return repairGoogleFirewallWithGuard(uids, run, nil)
}

func repairGoogleFirewallWithGuard(uids map[uint32]bool, run func(string, ...string) ([]byte, error), beforeMutation func() error) (int, error) {
	type familyRules struct {
		tool  string
		rules [][]string
	}
	var families []familyRules
	var failures error
	for _, tool := range []string{"iptables", "ip6tables"} {
		rules, err := run(tool, "-w", "2", "-t", "filter", "-S")
		if errors.Is(err, os.ErrNotExist) {
			continue
		}
		if err != nil {
			failures = errors.Join(failures, fmt.Errorf("%s: 读取规则失败: %w", tool, err))
			continue
		}
		family := familyRules{tool: tool}
		for line := range strings.SplitSeq(string(rules), "\n") {
			rule := strings.Fields(line)
			if isGoogleFirewallRule(rule, uids) {
				family.rules = append(family.rules, rule)
			}
		}
		families = append(families, family)
	}
	if failures != nil {
		return 0, failures
	}
	removed := 0
	for _, family := range families {
		for _, rule := range family.rules {
			if beforeMutation != nil {
				if err := beforeMutation(); err != nil {
					return removed, err
				}
			}
			if _, err := run(family.tool, append([]string{"-w", "2", "-t", "filter", "-D"}, rule[1:]...)...); err != nil {
				return removed, fmt.Errorf("%s: 删除匹配规则失败: %w", family.tool, err)
			}
			removed++
		}
	}
	return removed, failures
}

// isGoogleFirewallRule deliberately accepts only the observed vendor chain,
// exact owner match, a single decimal UID and a terminal DROP/REJECT verdict.
// Any extra match, range, inversion, other chain or unknown verdict is left as-is.
func isGoogleFirewallRule(rule []string, uids map[uint32]bool) bool {
	if len(rule) != 8 && len(rule) != 10 {
		return false
	}
	if rule[0] != "-A" || rule[1] != googleFirewallChain || rule[2] != "-m" || rule[3] != "owner" ||
		rule[4] != "--uid-owner" || rule[6] != "-j" {
		return false
	}
	uid, err := strconv.ParseUint(rule[5], 10, 32)
	if err != nil || !uids[uint32(uid)] {
		return false
	}
	if rule[7] == "DROP" && len(rule) == 8 {
		return true
	}
	return rule[7] == "REJECT" && (len(rule) == 8 ||
		(rule[8] == "--reject-with" && rule[9] != "" && !strings.HasPrefix(rule[9], "-")))
}

// parseGooglePackageUIDs accepts only the stable `cmd package list ... -U`
// machine format. Any malformed package row fails closed rather than risking
// deletion based on partial or ambiguous app identity.
func parseGooglePackageUIDs(output string) (map[string]uint32, error) {
	packages := make(map[string]uint32)
	scanner := bufio.NewScanner(strings.NewReader(output))
	for scanner.Scan() {
		line := strings.TrimSpace(scanner.Text())
		if !strings.HasPrefix(line, "package:") {
			continue
		}
		fields := strings.Fields(line)
		if len(fields) < 2 || !strings.HasPrefix(fields[1], "uid:") {
			return nil, errors.New("google_package_uid_invalid")
		}
		name := strings.TrimPrefix(fields[0], "package:")
		if !validAndroidPackageName(name) {
			return nil, errors.New("google_package_name_invalid")
		}
		uid, err := strconv.ParseUint(strings.TrimPrefix(fields[1], "uid:"), 10, 32)
		if err != nil {
			return nil, errors.New("google_package_uid_invalid")
		}
		if previous, exists := packages[name]; exists && previous != uint32(uid) {
			return nil, errors.New("google_package_uid_conflict")
		}
		packages[name] = uint32(uid)
	}
	if err := scanner.Err(); err != nil {
		return nil, errors.New("google_package_list_unreadable")
	}
	return packages, nil
}

func validAndroidPackageName(name string) bool {
	if name == "" {
		return false
	}
	for _, char := range name {
		if !(char == '.' || char == '_' || char >= 'a' && char <= 'z' ||
			char >= 'A' && char <= 'Z' || char >= '0' && char <= '9') {
			return false
		}
	}
	return true
}

// exclusiveGooglePackageUIDs returns only app UIDs owned exclusively by the
// named Google packages. Shared-UID apps outside the allowlist are protected.
func exclusiveGooglePackageUIDs(packages map[string]uint32, names []string) map[uint32]bool {
	uids := make(map[uint32]bool, len(names))
	allowed := make(map[string]bool, len(names))
	for _, name := range names {
		allowed[name] = true
		if uid, ok := packages[name]; ok && uid >= 10000 {
			uids[uid] = true
		}
	}
	for name, uid := range packages {
		if uids[uid] && !allowed[name] {
			delete(uids, uid)
		}
	}
	return uids
}

func googleFirewallBrand(brand string) bool {
	switch strings.ToLower(strings.TrimSpace(brand)) {
	case "redmagic", "nubia", "zte":
		return true
	default:
		return false
	}
}

func colorOS16GoogleFirewall(brand, rom string) bool {
	brand = strings.ToLower(strings.TrimSpace(brand))
	rom = strings.TrimSpace(rom)
	return (brand == "oneplus" || brand == "oppo") && (rom == "V16" || strings.HasPrefix(rom, "V16."))
}

func colorOSGooglePolicyUnset(value string) bool {
	value = strings.TrimSpace(value)
	return value == "null" || value == "0"
}

func colorOSGoogleRepairAllowed(currentUser, restriction, manualPolicy, trafficPolicy string) bool {
	return strings.TrimSpace(currentUser) == "0" && strings.TrimSpace(restriction) == "1" &&
		colorOSGooglePolicyUnset(manualPolicy) && colorOSGooglePolicyUnset(trafficPolicy)
}

func colorOSGoogleBPFCompatible(programType uint32, tag [8]byte, programMapCount, programMapID,
	mapType, mapID, keySize, valueSize uint32) bool {
	wantTag := [8]byte{0x71, 0x0a, 0xde, 0xf2, 0xa6, 0x3c, 0x67, 0xac}
	const socketFilterProgramType uint32 = 1
	const hashMapType uint32 = 1
	return programType == socketFilterProgramType && tag == wantTag && programMapCount == 1 &&
		programMapID != 0 && programMapID == mapID && mapType == hashMapType && keySize == 4 && valueSize == 4
}

func repairColorOSGoogleMaps(kinds []string, preflight func(string) error, beforeMutation func() error, repair func(string) (int, error)) (int, error) {
	var verified []string
	for _, kind := range kinds {
		err := preflight(kind)
		if errors.Is(err, os.ErrNotExist) {
			continue
		}
		if err != nil {
			return 0, fmt.Errorf("ColorOS %s BPF preflight: %w", kind, err)
		}
		verified = append(verified, kind)
	}
	var removed int
	for _, kind := range verified {
		if beforeMutation != nil {
			if err := beforeMutation(); err != nil {
				return removed, err
			}
		}
		count, err := repair(kind)
		removed += count
		if err != nil {
			return removed, fmt.Errorf("ColorOS %s BPF: %w", kind, err)
		}
	}
	return removed, nil
}

func repairColorOSGoogleEntries(uids map[uint32]bool, lookup func(uint32) (uint32, error), deleteEntry func(uint32) error, beforeMutation func() error) (int, error) {
	removed := 0
	for uid := range uids {
		value, err := lookup(uid)
		if errors.Is(err, os.ErrNotExist) {
			continue
		}
		if err != nil {
			return removed, err
		}
		if !colorOSGoogleBPFEntryBlocked(value) {
			continue
		}
		if beforeMutation != nil {
			if err := beforeMutation(); err != nil {
				return removed, err
			}
		}
		if err := deleteEntry(uid); errors.Is(err, os.ErrNotExist) {
			continue
		} else if err != nil {
			return removed, err
		}
		removed++
	}
	return removed, nil
}

func colorOSGoogleBPFEntryBlocked(value uint32) bool {
	return value == 1
}
