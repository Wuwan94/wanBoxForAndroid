// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package module

import (
	"slices"
	"strings"
	"testing"
)

func TestGoogleFirewallDeletesOnlyExactGoogleUIDRules(t *testing.T) {
	v4 := "-A zte_fw_gms -m owner --uid-owner 10123 -j DROP"
	v6 := "-A zte_fw_gms -m owner --uid-owner 10456 -j REJECT --reject-with icmp6-adm-prohibited"
	untouched := []string{
		"-A fw_OUTPUT -m owner --uid-owner 10123 -j DROP",
		"-A fw_OUTPUT_oplus_dns -m owner --uid-owner 10123 -j DROP",
		"-A fw_INPUT -i wlan+ -m bpf --object-pinned /sys/fs/bpf/prog_oplus-netd_skfilter_reject_wlan_uid -j REJECT",
		"-A fw_OUTPUT -o wlan+ -m bpf --object-pinned /sys/fs/bpf/prog_oplus-netd_skfilter_reject_wlan_uid -j REJECT",
		"-A fw_OUTPUT_oplus_dns -p udp --dport 53 -m string --string example.invalid --algo bm -j REJECT",
		"-A fw_OUTPUT -s ::1/128 ! -o lo -j DROP",
		"-A zte_fw_gms -m string --string \"DROP -m owner --uid-owner 10123\" -j ACCEPT",
		"-A zte_fw_gms -m owner --uid-owner 10299 -j DROP",
		"-A zte_fw_gms -o wlan+ -m owner --uid-owner 10123 -j DROP",
		"-A zte_fw_gms -m owner --uid-owner 10123-10456 -j DROP",
		"-A zte_fw_gms -m owner ! --uid-owner 10123 -j REJECT",
		"-A zte_fw_gms -j DROP",
	}
	rules := map[string][]string{
		"iptables":  append(slices.Clone(untouched), v4),
		"ip6tables": append(slices.Clone(untouched), v6),
	}
	run := func(tool string, args ...string) ([]byte, error) {
		t.Helper()
		if tool != "iptables" && tool != "ip6tables" {
			t.Fatalf("unexpected firewall tool: %s", tool)
		}
		if len(args) < 5 || !slices.Equal(args[:4], []string{"-w", "2", "-t", "filter"}) {
			t.Fatalf("unexpected firewall arguments: %s %v", tool, args)
		}
		switch args[4] {
		case "-S":
			return []byte(strings.Join(rules[tool], "\n")), nil
		case "-D":
			spec := "-A " + strings.Join(args[5:], " ")
			want := map[string]string{"iptables": v4, "ip6tables": v6}[tool]
			if spec != want {
				t.Fatalf("attempted to delete non-target rule: %s %s", tool, spec)
			}
			i := slices.Index(rules[tool], spec)
			if i < 0 {
				t.Fatalf("duplicate deletion: %s %s", tool, spec)
			}
			rules[tool] = slices.Delete(rules[tool], i, i+1)
			return nil, nil
		default:
			t.Fatalf("unexpected firewall operation: %v", args)
			return nil, nil
		}
	}

	uids := map[uint32]bool{10123: true, 10456: true}
	if count, err := repairGoogleFirewall(uids, run); count != 2 || err != nil {
		t.Fatalf("first pass: count=%d, err=%v", count, err)
	}
	for tool, current := range rules {
		if !slices.Equal(current, untouched) {
			t.Fatalf("%s changed shared, DNS, generic or non-target rules: %v", tool, current)
		}
	}
	if count, err := repairGoogleFirewall(uids, run); count != 0 || err != nil {
		t.Fatalf("second pass should be idempotent: count=%d, err=%v", count, err)
	}
}

func TestGooglePackageUIDsExcludeSharedAndUntrustedEntries(t *testing.T) {
	output := strings.Join([]string{
		"package:com.google.android.gms uid:10123",
		"package:com.android.vending uid:10124",
		"package:vendor.shared uid:10123",
		"package:com.android.configupdater uid:9999",
		"not-a-package-line",
	}, "\n")
	packages, err := parseGooglePackageUIDs(output)
	if err != nil {
		t.Fatal(err)
	}
	got := exclusiveGooglePackageUIDs(packages, []string{"com.google.android.gms", "com.android.vending"})
	if len(got) != 1 || !got[10124] {
		t.Fatalf("exclusive Google app UIDs = %v, want only vending UID 10124", got)
	}
	for _, invalid := range []string{
		"package:com.google.android.gms",
		"package:com.google.android.gms uid:not-a-number",
		"package:bad/name uid:10123",
	} {
		if _, err := parseGooglePackageUIDs(invalid); err == nil {
			t.Fatalf("malformed package service result accepted: %q", invalid)
		}
	}
}

func TestGoogleFirewallFirmwareGatesFailClosed(t *testing.T) {
	for _, brand := range []string{" REDMAGIC\n", "nubia", "ZTE"} {
		if !googleFirewallBrand(brand) {
			t.Errorf("recognized brand rejected: %q", brand)
		}
	}
	for _, brand := range []string{"OnePlus", "generic", "", "unknown"} {
		if googleFirewallBrand(brand) {
			t.Errorf("unknown or unrelated brand accepted: %q", brand)
		}
	}
	for _, tc := range []struct{ brand, rom string }{
		{"OnePlus\n", "V16.0.0\n"}, {"OPPO", "V16"},
	} {
		if !colorOS16GoogleFirewall(tc.brand, tc.rom) {
			t.Errorf("verified ColorOS 16 target rejected: brand=%q ROM=%q", tc.brand, tc.rom)
		}
	}
	for _, tc := range []struct{ brand, rom string }{
		{"OnePlus", ""}, {"OnePlus", "V17.0.0"}, {"other", "V16.0.0"}, {"RedMagic", "V16"},
	} {
		if colorOS16GoogleFirewall(tc.brand, tc.rom) {
			t.Errorf("unverified ColorOS target accepted: brand=%q ROM=%q", tc.brand, tc.rom)
		}
	}
	for _, value := range []string{"", "1", "4", "-1", "error"} {
		if colorOSGooglePolicyUnset(value) {
			t.Errorf("manual or unknown vendor policy accepted: %q", value)
		}
	}
	for _, value := range []string{"null\n", "0"} {
		if !colorOSGooglePolicyUnset(value) {
			t.Errorf("known unset vendor policy rejected: %q", value)
		}
	}
}

func TestColorOSGoogleRepairRequiresAllExplicitPolicyGates(t *testing.T) {
	if !colorOSGoogleRepairAllowed("0\n", "1\n", "null\n", "0") {
		t.Fatal("verified primary-user ColorOS gate rejected")
	}
	for _, tc := range []struct{ user, restriction, manual, traffic string }{
		{"10", "1", "null", "0"},
		{"0", "0", "null", "0"},
		{"0", "1", "1", "0"},
		{"0", "1", "null", "2"},
		{"0", "1", "error", "null"},
	} {
		if colorOSGoogleRepairAllowed(tc.user, tc.restriction, tc.manual, tc.traffic) {
			t.Errorf("incomplete/unknown policy gate accepted: %+v", tc)
		}
	}
}

func TestColorOSGoogleBPFBindingRequiresExactProgramAndMapShape(t *testing.T) {
	tag := [8]byte{0x71, 0x0a, 0xde, 0xf2, 0xa6, 0x3c, 0x67, 0xac}
	if !colorOSGoogleBPFCompatible(1, tag, 1, 42, 1, 42, 4, 4) {
		t.Fatal("verified vendor program/map pair rejected")
	}
	for _, tc := range []struct {
		programType, programMapCount, programMapID uint32
		tag                                        [8]byte
		mapType, mapID, keySize, valueSize         uint32
	}{
		{2, 1, 42, tag, 1, 42, 4, 4},
		{1, 1, 42, [8]byte{}, 1, 42, 4, 4},
		{1, 2, 42, tag, 1, 42, 4, 4},
		{1, 1, 41, tag, 1, 42, 4, 4},
		{1, 1, 42, tag, 2, 42, 4, 4},
		{1, 1, 42, tag, 1, 41, 4, 4},
		{1, 1, 42, tag, 1, 42, 8, 4},
		{1, 1, 42, tag, 1, 42, 4, 8},
	} {
		if colorOSGoogleBPFCompatible(tc.programType, tc.tag, tc.programMapCount, tc.programMapID,
			tc.mapType, tc.mapID, tc.keySize, tc.valueSize) {
			t.Errorf("unknown or mismatched vendor BPF shape accepted: %+v", tc)
		}
	}
}

func TestColorOSGoogleBPFOnlyDeletesRejectValue(t *testing.T) {
	for value, want := range map[uint32]bool{0: false, 1: true, 2: false, ^uint32(0): false} {
		if got := colorOSGoogleBPFEntryBlocked(value); got != want {
			t.Errorf("value %d blocked=%t, want %t", value, got, want)
		}
	}
}
