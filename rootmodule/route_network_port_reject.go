// @author 雾晚
package module

import (
	"errors"
	"math"
)

const rootRouteNetworkPortRejectMarker = "_wanbox_root_route_network_port_reject_v32"
const maxRootRouteNetworkPortRejectRuleID int64 = 9_007_199_254_740_991

type RootRouteNetworkPortRejectRulesSpec struct {
	Rules []RootRouteNetworkPortRejectRuleSpec `json:"rules"`
}

type RootRouteNetworkPortRejectRuleSpec struct {
	RuleID      int64  `json:"ruleId"`
	NetworkText string `json:"networkText"`
	PortText    string `json:"portText"`
}

func routeNetworkPortRejectMarkerValue(rule map[string]any) (map[string]any, bool, error) {
	value, exists := rule[rootRouteNetworkPortRejectMarker]
	if !exists {
		return nil, false, nil
	}
	marker, ok := value.(map[string]any)
	if len(rule) != 1 || !ok || len(marker) != 3 {
		return nil, true, errors.New("root_route_network_port_reject_invalid")
	}
	if _, ok = marker["ruleId"]; !ok {
		return nil, true, errors.New("root_route_network_port_reject_invalid")
	}
	if _, ok = marker["networkText"]; !ok {
		return nil, true, errors.New("root_route_network_port_reject_invalid")
	}
	if _, ok = marker["portText"]; !ok {
		return nil, true, errors.New("root_route_network_port_reject_invalid")
	}
	return marker, true, nil
}

func validateRootRouteNetworkPortRejectRules(spec *RootRouteNetworkPortRejectRulesSpec, config map[string]any) error {
	if spec == nil || len(spec.Rules) == 0 || len(spec.Rules) > 4096 {
		return errors.New("root_route_network_port_reject_invalid")
	}
	seen := make(map[int64]struct{}, len(spec.Rules))
	for _, item := range spec.Rules {
		if item.RuleID < 1 || item.RuleID > maxRootRouteNetworkPortRejectRuleID {
			return errors.New("root_route_network_port_reject_invalid")
		}
		if _, exists := seen[item.RuleID]; exists {
			return errors.New("root_route_network_port_reject_invalid")
		}
		seen[item.RuleID] = struct{}{}
		if _, err := parseRootRouteNetworkRejectNetworks(item.NetworkText); err != nil {
			return errors.New("root_route_network_port_reject_invalid")
		}
		if _, err := parseRootPortRejectPorts(item.PortText); err != nil {
			return errors.New("root_route_network_port_reject_invalid")
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
		marker, exists, err := routeNetworkPortRejectMarkerValue(rule)
		if err != nil {
			return err
		}
		if !exists {
			continue
		}
		if found >= len(spec.Rules) {
			return errors.New("root_route_network_port_reject_invalid")
		}
		id, ok := marker["ruleId"].(float64)
		if !ok || math.IsNaN(id) || math.IsInf(id, 0) || math.Trunc(id) != id || id < 1 || id > float64(maxRootRouteNetworkPortRejectRuleID) {
			return errors.New("root_route_network_port_reject_invalid")
		}
		networkText, ok1 := marker["networkText"].(string)
		portText, ok2 := marker["portText"].(string)
		if !ok1 || !ok2 {
			return errors.New("root_route_network_port_reject_invalid")
		}
		if _, err := parseRootRouteNetworkRejectNetworks(networkText); err != nil {
			return errors.New("root_route_network_port_reject_invalid")
		}
		if _, err := parseRootPortRejectPorts(portText); err != nil {
			return errors.New("root_route_network_port_reject_invalid")
		}
		want := spec.Rules[found]
		if int64(id) != want.RuleID || networkText != want.NetworkText || portText != want.PortText {
			return errors.New("root_route_network_port_reject_invalid")
		}
		found++
	}
	if found != len(spec.Rules) {
		return errors.New("root_route_network_port_reject_invalid")
	}
	return nil
}

func hasRootRouteNetworkPortRejectMarker(config map[string]any) bool {
	route, _ := config["route"].(map[string]any)
	rules, _ := route["rules"].([]any)
	for _, raw := range rules {
		if rule, ok := raw.(map[string]any); ok {
			if _, exists := rule[rootRouteNetworkPortRejectMarker]; exists {
				return true
			}
		}
	}
	return false
}

// Replace only matching raw-input markers at their original positions.
func materializeRootRouteNetworkPortRejectRules(route map[string]any, spec *RootRouteNetworkPortRejectRulesSpec) error {
	if err := validateRootRouteNetworkPortRejectRules(spec, map[string]any{"route": route}); err != nil {
		return err
	}
	rules, _ := route["rules"].([]any)
	for index, raw := range rules {
		rule, ok := raw.(map[string]any)
		if !ok {
			continue
		}
		marker, exists, err := routeNetworkPortRejectMarkerValue(rule)
		if err != nil {
			return err
		}
		if !exists {
			continue
		}
		networkText, _ := marker["networkText"].(string)
		portText, _ := marker["portText"].(string)
		networks, err := parseRootRouteNetworkRejectNetworks(networkText)
		if err != nil {
			return err
		}
		ports, err := parseRootPortRejectPorts(portText)
		if err != nil {
			return err
		}
		rules[index] = map[string]any{"network": networks, "port": ports, "action": "reject"}
	}
	route["rules"] = rules
	return nil
}
