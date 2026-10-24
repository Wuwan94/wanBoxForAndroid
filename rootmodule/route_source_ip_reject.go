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

const rootRouteSourceIPRejectMarker = "_wanbox_root_route_source_ip_reject_v35"
const maxRootRouteSourceIPRejectRuleID int64 = 9_007_199_254_740_991

type RootRouteSourceIPRejectRulesSpec struct {
	Rules []RootRouteSourceIPRejectRuleSpec `json:"rules"`
}

type RootRouteSourceIPRejectRuleSpec struct {
	RuleID     int64  `json:"ruleId"`
	SourceText string `json:"sourceText"`
}

// parseRootRouteSourceIPRejectCIDRs mirrors listByLineOrComma and accepts only
// explicit CIDRs supported by net.ParseCIDR; the original tokens are retained.
func parseRootRouteSourceIPRejectCIDRs(raw string) ([]string, error) {
	if len(utf16.Encode([]rune(raw))) > 4096 {
		return nil, errors.New("root_route_source_ip_reject_invalid")
	}
	parts := strings.FieldsFunc(raw, func(r rune) bool { return r == ',' || r == '\n' })
	cidrs := make([]string, 0, len(parts))
	for _, part := range parts {
		token := strings.TrimSpace(part)
		if token == "" {
			continue
		}
		if len(cidrs) >= 256 || strings.Count(token, "/") != 1 {
			return nil, errors.New("root_route_source_ip_reject_invalid")
		}
		if _, _, err := net.ParseCIDR(token); err != nil {
			return nil, errors.New("root_route_source_ip_reject_invalid")
		}
		cidrs = append(cidrs, token)
	}
	if len(cidrs) == 0 {
		return nil, errors.New("root_route_source_ip_reject_invalid")
	}
	return cidrs, nil
}

func routeSourceIPRejectMarkerValue(rule map[string]any) (map[string]any, bool, error) {
	value, exists := rule[rootRouteSourceIPRejectMarker]
	if !exists {
		return nil, false, nil
	}
	marker, ok := value.(map[string]any)
	if len(rule) != 1 || !ok || len(marker) != 2 {
		return nil, true, errors.New("root_route_source_ip_reject_invalid")
	}
	if _, ok = marker["ruleId"]; !ok {
		return nil, true, errors.New("root_route_source_ip_reject_invalid")
	}
	if _, ok = marker["sourceText"]; !ok {
		return nil, true, errors.New("root_route_source_ip_reject_invalid")
	}
	return marker, true, nil
}

func validateRootRouteSourceIPRejectRules(spec *RootRouteSourceIPRejectRulesSpec, config map[string]any) error {
	if spec == nil || len(spec.Rules) == 0 || len(spec.Rules) > 4096 {
		return errors.New("root_route_source_ip_reject_invalid")
	}
	seen := make(map[int64]struct{}, len(spec.Rules))
	for _, item := range spec.Rules {
		if item.RuleID < 1 || item.RuleID > maxRootRouteSourceIPRejectRuleID {
			return errors.New("root_route_source_ip_reject_invalid")
		}
		if _, exists := seen[item.RuleID]; exists {
			return errors.New("root_route_source_ip_reject_invalid")
		}
		seen[item.RuleID] = struct{}{}
		if _, err := parseRootRouteSourceIPRejectCIDRs(item.SourceText); err != nil {
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
		marker, exists, err := routeSourceIPRejectMarkerValue(rule)
		if err != nil {
			return err
		}
		if !exists {
			continue
		}
		if found >= len(spec.Rules) {
			return errors.New("root_route_source_ip_reject_invalid")
		}
		id, ok := marker["ruleId"].(float64)
		if !ok || math.IsNaN(id) || math.IsInf(id, 0) || math.Trunc(id) != id || id < 1 || id > float64(maxRootRouteSourceIPRejectRuleID) {
			return errors.New("root_route_source_ip_reject_invalid")
		}
		text, ok := marker["sourceText"].(string)
		if !ok {
			return errors.New("root_route_source_ip_reject_invalid")
		}
		if _, err := parseRootRouteSourceIPRejectCIDRs(text); err != nil {
			return err
		}
		want := spec.Rules[found]
		if int64(id) != want.RuleID || text != want.SourceText {
			return errors.New("root_route_source_ip_reject_invalid")
		}
		found++
	}
	if found != len(spec.Rules) {
		return errors.New("root_route_source_ip_reject_invalid")
	}
	return nil
}

func hasRootRouteSourceIPRejectMarker(config map[string]any) bool {
	route, _ := config["route"].(map[string]any)
	rules, _ := route["rules"].([]any)
	for _, raw := range rules {
		if rule, ok := raw.(map[string]any); ok {
			if _, exists := rule[rootRouteSourceIPRejectMarker]; exists {
				return true
			}
		}
	}
	return false
}

// Replace the raw source-CIDR marker in place to preserve user-rule order.
func materializeRootRouteSourceIPRejectRules(route map[string]any, spec *RootRouteSourceIPRejectRulesSpec) error {
	if err := validateRootRouteSourceIPRejectRules(spec, map[string]any{"route": route}); err != nil {
		return err
	}
	rules, _ := route["rules"].([]any)
	for index, raw := range rules {
		rule, ok := raw.(map[string]any)
		if !ok {
			continue
		}
		marker, exists, err := routeSourceIPRejectMarkerValue(rule)
		if err != nil {
			return err
		}
		if !exists {
			continue
		}
		text, _ := marker["sourceText"].(string)
		cidrs, err := parseRootRouteSourceIPRejectCIDRs(text)
		if err != nil {
			return err
		}
		rules[index] = map[string]any{"source_ip_cidr": cidrs, "action": "reject"}
	}
	route["rules"] = rules
	return nil
}
