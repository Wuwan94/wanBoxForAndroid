// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package module

import (
	"errors"
	"math"
	"strings"
)

const rootRouteProtocolRejectMarker = "_wanbox_root_route_protocol_reject_v34"
const maxRootRouteProtocolRejectRuleID int64 = 9_007_199_254_740_991

var rootRouteRejectProtocols = map[string]struct{}{
	"tls": {}, "http": {}, "quic": {}, "dns": {}, "stun": {},
	"bittorrent": {}, "dtls": {}, "ssh": {}, "rdp": {}, "ntp": {},
}

type RootRouteProtocolRejectRulesSpec struct {
	Rules []RootRouteProtocolRejectRuleSpec `json:"rules"`
}

type RootRouteProtocolRejectRuleSpec struct {
	RuleID       int64  `json:"ruleId"`
	ProtocolText string `json:"protocolText"`
}

func parseRootRouteRejectProtocols(raw string) ([]string, error) {
	if len(raw) > 4096 {
		return nil, errors.New("root_route_protocol_reject_invalid")
	}
	parts := strings.FieldsFunc(raw, func(r rune) bool { return r == ',' || r == '\n' })
	protocols := make([]string, 0, len(parts))
	for _, part := range parts {
		protocol := strings.TrimSpace(part)
		if protocol == "" {
			continue
		}
		if _, ok := rootRouteRejectProtocols[protocol]; !ok {
			return nil, errors.New("root_route_protocol_reject_invalid")
		}
		protocols = append(protocols, protocol)
		if len(protocols) > 256 {
			return nil, errors.New("root_route_protocol_reject_invalid")
		}
	}
	if len(protocols) == 0 {
		return nil, errors.New("root_route_protocol_reject_invalid")
	}
	return protocols, nil
}

func routeProtocolRejectMarkerValue(rule map[string]any) (map[string]any, bool, error) {
	value, exists := rule[rootRouteProtocolRejectMarker]
	if !exists {
		return nil, false, nil
	}
	marker, ok := value.(map[string]any)
	if len(rule) != 1 || !ok || len(marker) != 2 {
		return nil, true, errors.New("root_route_protocol_reject_invalid")
	}
	if _, ok = marker["ruleId"]; !ok {
		return nil, true, errors.New("root_route_protocol_reject_invalid")
	}
	if _, ok = marker["protocolText"]; !ok {
		return nil, true, errors.New("root_route_protocol_reject_invalid")
	}
	return marker, true, nil
}

func validateRootRouteProtocolRejectRules(spec *RootRouteProtocolRejectRulesSpec, config map[string]any) error {
	if spec == nil || len(spec.Rules) == 0 || len(spec.Rules) > 4096 {
		return errors.New("root_route_protocol_reject_invalid")
	}
	seen := make(map[int64]struct{}, len(spec.Rules))
	for _, item := range spec.Rules {
		if item.RuleID < 1 || item.RuleID > maxRootRouteProtocolRejectRuleID {
			return errors.New("root_route_protocol_reject_invalid")
		}
		if _, exists := seen[item.RuleID]; exists {
			return errors.New("root_route_protocol_reject_invalid")
		}
		seen[item.RuleID] = struct{}{}
		if _, err := parseRootRouteRejectProtocols(item.ProtocolText); err != nil {
			return errors.New("root_route_protocol_reject_invalid")
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
		marker, exists, err := routeProtocolRejectMarkerValue(rule)
		if err != nil {
			return err
		}
		if !exists {
			continue
		}
		if found >= len(spec.Rules) {
			return errors.New("root_route_protocol_reject_invalid")
		}
		id, ok := marker["ruleId"].(float64)
		if !ok || math.IsNaN(id) || math.IsInf(id, 0) || math.Trunc(id) != id || id < 1 || id > float64(maxRootRouteProtocolRejectRuleID) {
			return errors.New("root_route_protocol_reject_invalid")
		}
		text, ok := marker["protocolText"].(string)
		if !ok {
			return errors.New("root_route_protocol_reject_invalid")
		}
		if _, err := parseRootRouteRejectProtocols(text); err != nil {
			return errors.New("root_route_protocol_reject_invalid")
		}
		want := spec.Rules[found]
		if int64(id) != want.RuleID || text != want.ProtocolText {
			return errors.New("root_route_protocol_reject_invalid")
		}
		found++
	}
	if found != len(spec.Rules) {
		return errors.New("root_route_protocol_reject_invalid")
	}
	return nil
}

func hasRootRouteProtocolRejectMarker(config map[string]any) bool {
	route, _ := config["route"].(map[string]any)
	rules, _ := route["rules"].([]any)
	for _, raw := range rules {
		if rule, ok := raw.(map[string]any); ok {
			if _, exists := rule[rootRouteProtocolRejectMarker]; exists {
				return true
			}
		}
	}
	return false
}

// Replace raw protocol markers in place, leaving every other route at its original index.
func materializeRootRouteProtocolRejectRules(route map[string]any, spec *RootRouteProtocolRejectRulesSpec) error {
	if err := validateRootRouteProtocolRejectRules(spec, map[string]any{"route": route}); err != nil {
		return err
	}
	rules, _ := route["rules"].([]any)
	for index, raw := range rules {
		rule, ok := raw.(map[string]any)
		if !ok {
			continue
		}
		marker, exists, err := routeProtocolRejectMarkerValue(rule)
		if err != nil {
			return err
		}
		if !exists {
			continue
		}
		text, _ := marker["protocolText"].(string)
		protocols, err := parseRootRouteRejectProtocols(text)
		if err != nil {
			return err
		}
		rules[index] = map[string]any{"protocol": protocols, "action": "reject"}
	}
	route["rules"] = rules
	return nil
}
