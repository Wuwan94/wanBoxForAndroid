// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package module

import (
	"errors"
	"math"
)

const rootRouteProtocolPortRejectMarker = "_wanbox_root_route_protocol_port_reject_v39"
const maxRootRouteProtocolPortRejectRuleID int64 = 9_007_199_254_740_991

type RootRouteProtocolPortRejectRulesSpec struct {
	Rules []RootRouteProtocolPortRejectRuleSpec `json:"rules"`
}
type RootRouteProtocolPortRejectRuleSpec struct {
	RuleID       int64  `json:"ruleId"`
	ProtocolText string `json:"protocolText"`
	PortText     string `json:"portText"`
}

func routeProtocolPortRejectMarkerValue(rule map[string]any) (map[string]any, bool, error) {
	value, exists := rule[rootRouteProtocolPortRejectMarker]
	if !exists {
		return nil, false, nil
	}
	marker, ok := value.(map[string]any)
	if len(rule) != 1 || !ok || len(marker) != 3 {
		return nil, true, errors.New("root_route_protocol_port_reject_invalid")
	}
	for _, key := range []string{"ruleId", "protocolText", "portText"} {
		if _, ok = marker[key]; !ok {
			return nil, true, errors.New("root_route_protocol_port_reject_invalid")
		}
	}
	return marker, true, nil
}
func validateRootRouteProtocolPortRejectRules(spec *RootRouteProtocolPortRejectRulesSpec, config map[string]any) error {
	if spec == nil || len(spec.Rules) == 0 || len(spec.Rules) > 4096 {
		return errors.New("root_route_protocol_port_reject_invalid")
	}
	seen := make(map[int64]struct{}, len(spec.Rules))
	for _, item := range spec.Rules {
		if item.RuleID < 1 || item.RuleID > maxRootRouteProtocolPortRejectRuleID {
			return errors.New("root_route_protocol_port_reject_invalid")
		}
		if _, ok := seen[item.RuleID]; ok {
			return errors.New("root_route_protocol_port_reject_invalid")
		}
		seen[item.RuleID] = struct{}{}
		if _, err := parseRootRouteRejectProtocols(item.ProtocolText); err != nil {
			return errors.New("root_route_protocol_port_reject_invalid")
		}
		if _, err := parseRootPortRejectPorts(item.PortText); err != nil {
			return errors.New("root_route_protocol_port_reject_invalid")
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
		marker, exists, err := routeProtocolPortRejectMarkerValue(rule)
		if err != nil {
			return err
		}
		if !exists {
			continue
		}
		if found >= len(spec.Rules) {
			return errors.New("root_route_protocol_port_reject_invalid")
		}
		id, ok := marker["ruleId"].(float64)
		if !ok || math.IsNaN(id) || math.IsInf(id, 0) || math.Trunc(id) != id || id < 1 || id > float64(maxRootRouteProtocolPortRejectRuleID) {
			return errors.New("root_route_protocol_port_reject_invalid")
		}
		protocol, ok := marker["protocolText"].(string)
		if !ok {
			return errors.New("root_route_protocol_port_reject_invalid")
		}
		port, ok := marker["portText"].(string)
		if !ok {
			return errors.New("root_route_protocol_port_reject_invalid")
		}
		if _, err := parseRootRouteRejectProtocols(protocol); err != nil {
			return errors.New("root_route_protocol_port_reject_invalid")
		}
		if _, err := parseRootPortRejectPorts(port); err != nil {
			return errors.New("root_route_protocol_port_reject_invalid")
		}
		want := spec.Rules[found]
		if int64(id) != want.RuleID || protocol != want.ProtocolText || port != want.PortText {
			return errors.New("root_route_protocol_port_reject_invalid")
		}
		found++
	}
	if found != len(spec.Rules) {
		return errors.New("root_route_protocol_port_reject_invalid")
	}
	return nil
}
func hasRootRouteProtocolPortRejectMarker(config map[string]any) bool {
	route, _ := config["route"].(map[string]any)
	rules, _ := route["rules"].([]any)
	for _, raw := range rules {
		if rule, ok := raw.(map[string]any); ok {
			if _, exists := rule[rootRouteProtocolPortRejectMarker]; exists {
				return true
			}
		}
	}
	return false
}
func materializeRootRouteProtocolPortRejectRules(route map[string]any, spec *RootRouteProtocolPortRejectRulesSpec) error {
	if err := validateRootRouteProtocolPortRejectRules(spec, map[string]any{"route": route}); err != nil {
		return err
	}
	rules, _ := route["rules"].([]any)
	for index, raw := range rules {
		rule, ok := raw.(map[string]any)
		if !ok {
			continue
		}
		marker, exists, err := routeProtocolPortRejectMarkerValue(rule)
		if err != nil {
			return err
		}
		if !exists {
			continue
		}
		protocols, err := parseRootRouteRejectProtocols(marker["protocolText"].(string))
		if err != nil {
			return err
		}
		ports, err := parseRootPortRejectPorts(marker["portText"].(string))
		if err != nil {
			return err
		}
		rules[index] = map[string]any{"protocol": protocols, "port": ports, "action": "reject"}
	}
	route["rules"] = rules
	return nil
}
