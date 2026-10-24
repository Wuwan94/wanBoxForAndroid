// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package module

import (
	"errors"
	"math"
	"strings"
	"unicode"
)

const rootRouteAppUIDMarker = "_wanbox_root_route_app_uid_v45"

type RootRouteAppUIDRulesSpec struct {
	Rules []RootRouteAppUIDRuleSpec `json:"rules"`
}

type RootRouteAppUIDRuleSpec struct {
	Index    int      `json:"index"`
	Packages []string `json:"packages"`
	UIDs     []int    `json:"uids"`
	Outbound string   `json:"outbound"`
}

func validRootRouteAppUIDPackage(value string) bool {
	if value == "" || len(value) > 256 || strings.TrimSpace(value) != value || strings.ContainsAny(value, "/\\\x00") {
		return false
	}
	for _, r := range value {
		if unicode.IsControl(r) || unicode.IsSpace(r) {
			return false
		}
	}
	return true
}

func validateRootRouteAppUIDRules(spec *RootRouteAppUIDRulesSpec, config map[string]any, snapshot Snapshot) error {
	if spec == nil || len(spec.Rules) == 0 || len(spec.Rules) > 4096 {
		return errors.New("root_route_app_uid_invalid")
	}
	route, _ := config["route"].(map[string]any)
	rules, _ := route["rules"].([]any)
	found := 0
	for index, raw := range rules {
		rule, ok := raw.(map[string]any)
		if !ok {
			continue
		}
		value, exists := rule[rootRouteAppUIDMarker]
		if !exists {
			continue
		}
		if len(rule) != 1 || found >= len(spec.Rules) {
			return errors.New("root_route_app_uid_invalid")
		}
		marker, ok := value.(map[string]any)
		markerIndex, indexOK := marker["index"].(float64)
		item := spec.Rules[found]
		if !ok || len(marker) != 4 || !indexOK || math.IsNaN(markerIndex) || math.IsInf(markerIndex, 0) || math.Trunc(markerIndex) != markerIndex || markerIndex != float64(index) || item.Index != index ||
			len(item.Packages) == 0 || len(item.Packages) > 256 || len(item.UIDs) > 4096 || !validRootRouteNetworkOutboundTag(item.Outbound) || !hasRootOutboundTag(config, snapshot, item.Outbound) {
			return errors.New("root_route_app_uid_invalid")
		}
		seenPackages := map[string]bool{}
		for _, name := range item.Packages {
			if !validRootRouteAppUIDPackage(name) || seenPackages[name] {
				return errors.New("root_route_app_uid_invalid")
			}
			seenPackages[name] = true
		}
		seenUIDs := map[int]bool{}
		for _, uid := range item.UIDs {
			if uid < 0 || seenUIDs[uid] {
				return errors.New("root_route_app_uid_invalid")
			}
			seenUIDs[uid] = true
		}
		if marker["outbound"] != item.Outbound {
			return errors.New("root_route_app_uid_invalid")
		}
		markerPackages, ok := marker["packages"].([]any)
		if !ok || len(markerPackages) != len(item.Packages) {
			return errors.New("root_route_app_uid_invalid")
		}
		for i, value := range markerPackages {
			if value != item.Packages[i] {
				return errors.New("root_route_app_uid_invalid")
			}
		}
		markerUIDs, ok := marker["uids"].([]any)
		if !ok || len(markerUIDs) != len(item.UIDs) {
			return errors.New("root_route_app_uid_invalid")
		}
		for i, value := range markerUIDs {
			n, ok := value.(float64)
			if !ok || n != float64(item.UIDs[i]) {
				return errors.New("root_route_app_uid_invalid")
			}
		}
		found++
	}
	if found != len(spec.Rules) {
		return errors.New("root_route_app_uid_invalid")
	}
	return nil
}

func hasRootRouteAppUIDMarker(config map[string]any) bool {
	route, _ := config["route"].(map[string]any)
	rules, _ := route["rules"].([]any)
	for _, raw := range rules {
		if rule, ok := raw.(map[string]any); ok {
			if _, exists := rule[rootRouteAppUIDMarker]; exists {
				return true
			}
		}
	}
	return false
}

// Materialize only package-identity route rules; App passes Android-resolved UIDs,
// while the module owns the final matcher/action JSON and logical OR shape.
func materializeRootRouteAppUIDRules(route map[string]any, spec *RootRouteAppUIDRulesSpec) error {
	if spec == nil || len(spec.Rules) == 0 || len(spec.Rules) > 4096 {
		return errors.New("root_route_app_uid_invalid")
	}
	rules, _ := route["rules"].([]any)
	found := 0
	for index, raw := range rules {
		rule, ok := raw.(map[string]any)
		if !ok {
			continue
		}
		value, exists := rule[rootRouteAppUIDMarker]
		if !exists {
			continue
		}
		if len(rule) != 1 || found >= len(spec.Rules) {
			return errors.New("root_route_app_uid_invalid")
		}
		marker, ok := value.(map[string]any)
		item := spec.Rules[found]
		// Earlier Root migrations can insert rules before this marker after the
		// snapshot was validated. Preserve source order by replacing the marker
		// in its current slot; original indices are checked on the untouched snapshot.
		if !ok || len(marker) != 4 || item.Index < 0 {
			return errors.New("root_route_app_uid_invalid")
		}
		// Preserve the legacy generated rule's matcher shape: package_name and
		// user_id coexist as conjunctive fields; an empty UID resolution leaves
		// package_name as the only matcher.
		matcher := map[string]any{"package_name": append([]string(nil), item.Packages...), "outbound": item.Outbound}
		if len(item.UIDs) > 0 {
			matcher["user_id"] = append([]int(nil), item.UIDs...)
		}
		rules[index] = matcher
		found++
	}
	if found != len(spec.Rules) {
		return errors.New("root_route_app_uid_invalid")
	}
	route["rules"] = rules
	return nil
}
