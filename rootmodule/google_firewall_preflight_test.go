// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package module

import (
	"errors"
	"os"
	"testing"
)

func TestGoogleFirewallPreflightsBothFamiliesBeforeDeleting(t *testing.T) {
	deletes := 0
	run := func(tool string, args ...string) ([]byte, error) {
		t.Helper()
		if args[4] == "-S" {
			if tool == "iptables" {
				return []byte("-A zte_fw_gms -m owner --uid-owner 10123 -j DROP\n"), nil
			}
			return nil, errors.New("ip6tables permission denied")
		}
		if args[4] == "-D" {
			deletes++
		}
		return nil, nil
	}
	count, err := repairGoogleFirewall(map[uint32]bool{10123: true}, run)
	if err == nil || count != 0 || deletes != 0 {
		t.Fatalf("partial-family preflight: removed=%d deletes=%d err=%v; want error and no mutation", count, deletes, err)
	}
}

func TestColorOSGooglePreflightsAllPresentPinsBeforeDeleting(t *testing.T) {
	var checked, repaired []string
	unknown := errors.New("unknown pin layout")
	count, err := repairColorOSGoogleMaps([]string{"wlan", "qcom", "mtk"}, func(kind string) error {
		checked = append(checked, kind)
		if kind == "qcom" {
			return unknown
		}
		return nil
	}, nil, func(kind string) (int, error) {
		repaired = append(repaired, kind)
		return 1, nil
	})
	if !errors.Is(err, unknown) || count != 0 || len(repaired) != 0 || len(checked) != 2 {
		t.Fatalf("unknown pin preflight checked=%v repaired=%v count=%d err=%v; want no mutations", checked, repaired, count, err)
	}
}

func TestColorOSGooglePreflightSkipsAbsentPinsAndRepairsVerifiedPins(t *testing.T) {
	var repaired []string
	count, err := repairColorOSGoogleMaps([]string{"wlan", "qcom", "mtk"}, func(kind string) error {
		if kind == "qcom" {
			return os.ErrNotExist
		}
		return nil
	}, nil, func(kind string) (int, error) {
		repaired = append(repaired, kind)
		return 1, nil
	})
	if err != nil || count != 2 || len(repaired) != 2 || repaired[0] != "wlan" || repaired[1] != "mtk" {
		t.Fatalf("preflight repaired=%v count=%d err=%v; want wlan and mtk", repaired, count, err)
	}
}

func TestGoogleFirewallMutationGuardRunsImmediatelyBeforeDelete(t *testing.T) {
	blocked := errors.New("module_inactive")
	guardCalls, deletes := 0, 0
	run := func(tool string, args ...string) ([]byte, error) {
		if args[4] == "-S" {
			return []byte("-A zte_fw_gms -m owner --uid-owner 10123 -j DROP\n-A zte_fw_gms -m owner --uid-owner 10124 -j REJECT\n"), nil
		}
		if args[4] == "-D" {
			deletes++
		}
		return nil, nil
	}
	count, err := repairGoogleFirewallWithGuard(map[uint32]bool{10123: true, 10124: true}, run, func() error {
		guardCalls++
		if guardCalls == 2 {
			return blocked
		}
		return nil
	})
	if !errors.Is(err, blocked) || count != 1 || deletes != 1 || guardCalls != 2 {
		t.Fatalf("guard calls=%d count=%d deletes=%d err=%v; want one delete then stop", guardCalls, count, deletes, err)
	}
}

func TestColorOSGoogleEntryMutationGuardRunsBeforeEachDelete(t *testing.T) {
	blocked := errors.New("module_inactive")
	guardCalls := 0
	var deleted []uint32
	count, err := repairColorOSGoogleEntries(map[uint32]bool{10123: true, 10124: true},
		func(uint32) (uint32, error) { return 1, nil },
		func(uid uint32) error { deleted = append(deleted, uid); return nil },
		func() error {
			guardCalls++
			if guardCalls == 2 {
				return blocked
			}
			return nil
		})
	if !errors.Is(err, blocked) || count != 1 || len(deleted) != 1 || guardCalls != 2 {
		t.Fatalf("guard calls=%d count=%d deleted=%v err=%v; want one delete then stop", guardCalls, count, deleted, err)
	}
}

func TestGoogleFirewallDeleteFailureStopsOtherFamily(t *testing.T) {
	failure := errors.New("iptables delete failed")
	var deleted []string
	run := func(tool string, args ...string) ([]byte, error) {
		if args[4] == "-S" {
			return []byte("-A zte_fw_gms -m owner --uid-owner 10123 -j DROP\n"), nil
		}
		if args[4] == "-D" {
			deleted = append(deleted, tool)
			if tool == "iptables" {
				return nil, failure
			}
		}
		return nil, nil
	}
	count, err := repairGoogleFirewall(map[uint32]bool{10123: true}, run)
	if !errors.Is(err, failure) || count != 0 || len(deleted) != 1 || deleted[0] != "iptables" {
		t.Fatalf("removed=%d deleted=%v err=%v; want stop after IPv4 deletion error", count, deleted, err)
	}
}

func TestColorOSGoogleMapRepairFailureStopsLaterMaps(t *testing.T) {
	failure := errors.New("map lookup failed")
	var repaired []string
	count, err := repairColorOSGoogleMaps([]string{"wlan", "qcom", "mtk"}, func(string) error {
		return nil
	}, nil, func(kind string) (int, error) {
		repaired = append(repaired, kind)
		if kind == "wlan" {
			return 0, failure
		}
		return 1, nil
	})
	if !errors.Is(err, failure) || count != 0 || len(repaired) != 1 || repaired[0] != "wlan" {
		t.Fatalf("repaired=%v removed=%d err=%v; want stop after first map failure", repaired, count, err)
	}
}
