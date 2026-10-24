// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package module

import (
	"errors"
	"math"
	"net"
	"strings"
	"unicode/utf16"
)

const rootRouteIPRejectMarker = "_wanbox_root_route_ip_reject_v36"
const maxRootRouteIPRejectRuleID int64 = 9_007_199_254_740_991

type RootRouteIPRejectRulesSpec struct {
	Rules []RootRouteIPRejectRuleSpec `json:"rules"`
}

type RootRouteIPRejectRuleSpec struct {
	RuleID int64  `json:"ruleId"`
	IPText string `json:"ipText"`
}

// parseRootRouteIPRejectCIDRs mirrors listByLineOrComma and accepts only
// explicit CIDRs supported by net.ParseCIDR; the original tokens are retained.
func parseRootRouteIPRejectCIDRs(raw string) ([]string, error) {
	if len(utf16.Encode([]rune(raw))) > 4096 {
		return nil, errors.New("root_route_ip_reject_invalid")
	}
	parts := strings.FieldsFunc(raw, func(r rune) bool { return r == ',' || r == '\n' })
	cidrs := make([]string, 0, len(parts))
	for _, part := range parts {
		token := strings.TrimSpace(part)
		if token == "" {
			continue
		}
		if len(cidrs) >= 256 || strings.Count(token, "/") != 1 {
			return nil, errors.New("root_route_ip_reject_invalid")
		}
		if _, _, err := net.ParseCIDR(token); err != nil {
			return nil, errors.New("root_route_ip_reject_invalid")
		}
		cidrs = append(cidrs, token)
	}
	if len(cidrs) == 0 {
		return nil, errors.New("root_route_ip_reject_invalid")
	}
	return cidrs, nil
}

func routeIPRejectMarkerValue(rule map[string]any) (map[string]any, bool, error) {
	value, exists := rule[rootRouteIPRejectMarker]
	if !exists {
		return nil, false, nil
	}
	marker, ok := value.(map[string]any)
	if len(rule) != 1 || !ok || len(marker) != 2 {
		return nil, true, errors.New("root_route_ip_reject_invalid")
	}
	if _, ok = marker["ruleId"]; !ok {
		return nil, true, errors.New("root_route_ip_reject_invalid")
	}
	if _, ok = marker["ipText"]; !ok {
		return nil, true, errors.New("root_route_ip_reject_invalid")
	}
	return marker, true, nil
}

func validateRootRouteIPRejectRules(spec *RootRouteIPRejectRulesSpec, config map[string]any) error {
	if spec == nil || len(spec.Rules) == 0 || len(spec.Rules) > 4096 {
		return errors.New("root_route_ip_reject_invalid")
	}
	seen := make(map[int64]struct{}, len(spec.Rules))
	for _, item := range spec.Rules {
		if item.RuleID < 1 || item.RuleID > maxRootRouteIPRejectRuleID {
			return errors.New("root_route_ip_reject_invalid")
		}
		if _, exists := seen[item.RuleID]; exists {
			return errors.New("root_route_ip_reject_invalid")
		}
		seen[item.RuleID] = struct{}{}
		if _, err := parseRootRouteIPRejectCIDRs(item.IPText); err != nil {
			return err
		}
	}
	route, _ := config["route"].(map[string]any)
	rules, _ := route["rules"].([]any)
	found := 0
	for _, raw := range rules {
		rule, ok := raw.(map[string]any)
		if !ok {
			continue
		}
		marker, exists, err := routeIPRejectMarkerValue(rule)
		if err != nil {
			return err
		}
		if !exists {
			continue
		}
		if found >= len(spec.Rules) {
			return errors.New("root_route_ip_reject_invalid")
		}
		id, ok := marker["ruleId"].(float64)
		if !ok || math.IsNaN(id) || math.IsInf(id, 0) || math.Trunc(id) != id || id < 1 || id > float64(maxRootRouteIPRejectRuleID) {
			return errors.New("root_route_ip_reject_invalid")
		}
		text, ok := marker["ipText"].(string)
		if !ok {
			return errors.New("root_route_ip_reject_invalid")
		}
		if _, err := parseRootRouteIPRejectCIDRs(text); err != nil {
			return err
		}
		want := spec.Rules[found]
		if int64(id) != want.RuleID || text != want.IPText {
			return errors.New("root_route_ip_reject_invalid")
		}
		found++
	}
	if found != len(spec.Rules) {
		return errors.New("root_route_ip_reject_invalid")
	}
	return nil
}

func hasRootRouteIPRejectMarker(config map[string]any) bool {
	route, _ := config["route"].(map[string]any)
	rules, _ := route["rules"].([]any)
	for _, raw := range rules {
		if rule, ok := raw.(map[string]any); ok {
			if _, exists := rule[rootRouteIPRejectMarker]; exists {
				return true
			}
		}
	}
	return false
}

// Replace the raw destination-CIDR marker in place to preserve user-rule order.
func materializeRootRouteIPRejectRules(route map[string]any, spec *RootRouteIPRejectRulesSpec) error {
	if err := validateRootRouteIPRejectRules(spec, map[string]any{"route": route}); err != nil {
		return err
	}
	rules, _ := route["rules"].([]any)
	for index, raw := range rules {
		rule, ok := raw.(map[string]any)
		if !ok {
			continue
		}
		marker, exists, err := routeIPRejectMarkerValue(rule)
		if err != nil {
			return err
		}
		if !exists {
			continue
		}
		text, _ := marker["ipText"].(string)
		cidrs, err := parseRootRouteIPRejectCIDRs(text)
		if err != nil {
			return err
		}
		rules[index] = map[string]any{"ip_cidr": cidrs, "action": "reject"}
	}
	route["rules"] = rules
	return nil
}
