// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package module

import (
	"errors"
	"math"
)

const rootRouteSourceDestinationIPRejectMarker = "_wanbox_root_route_source_destination_ip_reject_v37"
const maxRootRouteSourceDestinationIPRejectRuleID int64 = 9_007_199_254_740_991

type RootRouteSourceDestinationIPRejectRulesSpec struct {
	Rules []RootRouteSourceDestinationIPRejectRuleSpec `json:"rules"`
}

type RootRouteSourceDestinationIPRejectRuleSpec struct {
	RuleID     int64  `json:"ruleId"`
	SourceText string `json:"sourceText"`
	IPText     string `json:"ipText"`
}

func routeSourceDestinationIPRejectMarkerValue(rule map[string]any) (map[string]any, bool, error) {
	value, exists := rule[rootRouteSourceDestinationIPRejectMarker]
	if !exists {
		return nil, false, nil
	}
	marker, ok := value.(map[string]any)
	if len(rule) != 1 || !ok || len(marker) != 3 {
		return nil, true, errors.New("root_route_source_destination_ip_reject_invalid")
	}
	if _, ok = marker["ruleId"]; !ok {
		return nil, true, errors.New("root_route_source_destination_ip_reject_invalid")
	}
	if _, ok = marker["sourceText"]; !ok {
		return nil, true, errors.New("root_route_source_destination_ip_reject_invalid")
	}
	if _, ok = marker["ipText"]; !ok {
		return nil, true, errors.New("root_route_source_destination_ip_reject_invalid")
	}
	return marker, true, nil
}

func validateRootRouteSourceDestinationIPRejectRules(spec *RootRouteSourceDestinationIPRejectRulesSpec, config map[string]any) error {
	if spec == nil || len(spec.Rules) == 0 || len(spec.Rules) > 4096 {
		return errors.New("root_route_source_destination_ip_reject_invalid")
	}
	seen := make(map[int64]struct{}, len(spec.Rules))
	for _, item := range spec.Rules {
		if item.RuleID < 1 || item.RuleID > maxRootRouteSourceDestinationIPRejectRuleID {
			return errors.New("root_route_source_destination_ip_reject_invalid")
		}
		if _, exists := seen[item.RuleID]; exists {
			return errors.New("root_route_source_destination_ip_reject_invalid")
		}
		seen[item.RuleID] = struct{}{}
		if _, err := parseRootRouteSourceIPRejectCIDRs(item.SourceText); err != nil {
			return err
		}
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
		marker, exists, err := routeSourceDestinationIPRejectMarkerValue(rule)
		if err != nil {
			return err
		}
		if !exists {
			continue
		}
		if found >= len(spec.Rules) {
			return errors.New("root_route_source_destination_ip_reject_invalid")
		}
		id, ok := marker["ruleId"].(float64)
		if !ok || math.IsNaN(id) || math.IsInf(id, 0) || math.Trunc(id) != id || id < 1 || id > float64(maxRootRouteSourceDestinationIPRejectRuleID) {
			return errors.New("root_route_source_destination_ip_reject_invalid")
		}
		source, ok := marker["sourceText"].(string)
		if !ok {
			return errors.New("root_route_source_destination_ip_reject_invalid")
		}
		ip, ok := marker["ipText"].(string)
		if !ok {
			return errors.New("root_route_source_destination_ip_reject_invalid")
		}
		if _, err := parseRootRouteSourceIPRejectCIDRs(source); err != nil {
			return err
		}
		if _, err := parseRootRouteIPRejectCIDRs(ip); err != nil {
			return err
		}
		want := spec.Rules[found]
		if int64(id) != want.RuleID || source != want.SourceText || ip != want.IPText {
			return errors.New("root_route_source_destination_ip_reject_invalid")
		}
		found++
	}
	if found != len(spec.Rules) {
		return errors.New("root_route_source_destination_ip_reject_invalid")
	}
	return nil
}

func hasRootRouteSourceDestinationIPRejectMarker(config map[string]any) bool {
	route, _ := config["route"].(map[string]any)
	rules, _ := route["rules"].([]any)
	for _, raw := range rules {
		if rule, ok := raw.(map[string]any); ok {
			if _, exists := rule[rootRouteSourceDestinationIPRejectMarker]; exists {
				return true
			}
		}
	}
	return false
}

// Replace the raw source-and-destination CIDR marker in place, preserving AND semantics and order.
func materializeRootRouteSourceDestinationIPRejectRules(route map[string]any, spec *RootRouteSourceDestinationIPRejectRulesSpec) error {
	if err := validateRootRouteSourceDestinationIPRejectRules(spec, map[string]any{"route": route}); err != nil {
		return err
	}
	rules, _ := route["rules"].([]any)
	for index, raw := range rules {
		rule, ok := raw.(map[string]any)
		if !ok {
			continue
		}
		marker, exists, err := routeSourceDestinationIPRejectMarkerValue(rule)
		if err != nil {
			return err
		}
		if !exists {
			continue
		}
		sourceText, _ := marker["sourceText"].(string)
		ipText, _ := marker["ipText"].(string)
		source, err := parseRootRouteSourceIPRejectCIDRs(sourceText)
		if err != nil {
			return err
		}
		ips, err := parseRootRouteIPRejectCIDRs(ipText)
		if err != nil {
			return err
		}
		rules[index] = map[string]any{"source_ip_cidr": source, "ip_cidr": ips, "action": "reject"}
	}
	route["rules"] = rules
	return nil
}
