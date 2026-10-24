// @author 雾晚
package module

import (
	"errors"
	"math"
)

const rootRouteSourcePortRejectMarker = "_wanbox_root_route_source_port_reject_v33"
const maxRootRouteSourcePortRejectRuleID int64 = 9_007_199_254_740_991

type RootRouteSourcePortRejectRulesSpec struct {
	Rules []RootRouteSourcePortRejectRuleSpec `json:"rules"`
}

type RootRouteSourcePortRejectRuleSpec struct {
	RuleID         int64  `json:"ruleId"`
	SourcePortText string `json:"sourcePortText"`
}

func routeSourcePortRejectMarkerValue(rule map[string]any) (map[string]any, bool, error) {
	value, exists := rule[rootRouteSourcePortRejectMarker]
	if !exists {
		return nil, false, nil
	}
	marker, ok := value.(map[string]any)
	if len(rule) != 1 || !ok || len(marker) != 2 {
		return nil, true, errors.New("root_route_source_port_reject_invalid")
	}
	if _, ok = marker["ruleId"]; !ok {
		return nil, true, errors.New("root_route_source_port_reject_invalid")
	}
	if _, ok = marker["sourcePortText"]; !ok {
		return nil, true, errors.New("root_route_source_port_reject_invalid")
	}
	return marker, true, nil
}

func validateRootRouteSourcePortRejectRules(spec *RootRouteSourcePortRejectRulesSpec, config map[string]any) error {
	if spec == nil || len(spec.Rules) == 0 || len(spec.Rules) > 4096 {
		return errors.New("root_route_source_port_reject_invalid")
	}
	seen := make(map[int64]struct{}, len(spec.Rules))
	for _, item := range spec.Rules {
		if item.RuleID < 1 || item.RuleID > maxRootRouteSourcePortRejectRuleID {
			return errors.New("root_route_source_port_reject_invalid")
		}
		if _, exists := seen[item.RuleID]; exists {
			return errors.New("root_route_source_port_reject_invalid")
		}
		seen[item.RuleID] = struct{}{}
		if _, err := parseRootPortRejectPorts(item.SourcePortText); err != nil {
			return errors.New("root_route_source_port_reject_invalid")
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
		marker, exists, err := routeSourcePortRejectMarkerValue(rule)
		if err != nil {
			return err
		}
		if !exists {
			continue
		}
		if found >= len(spec.Rules) {
			return errors.New("root_route_source_port_reject_invalid")
		}
		id, ok := marker["ruleId"].(float64)
		if !ok || math.IsNaN(id) || math.IsInf(id, 0) || math.Trunc(id) != id || id < 1 || id > float64(maxRootRouteSourcePortRejectRuleID) {
			return errors.New("root_route_source_port_reject_invalid")
		}
		text, ok := marker["sourcePortText"].(string)
		if !ok {
			return errors.New("root_route_source_port_reject_invalid")
		}
		if _, err := parseRootPortRejectPorts(text); err != nil {
			return errors.New("root_route_source_port_reject_invalid")
		}
		want := spec.Rules[found]
		if int64(id) != want.RuleID || text != want.SourcePortText {
			return errors.New("root_route_source_port_reject_invalid")
		}
		found++
	}
	if found != len(spec.Rules) {
		return errors.New("root_route_source_port_reject_invalid")
	}
	return nil
}

func hasRootRouteSourcePortRejectMarker(config map[string]any) bool {
	route, _ := config["route"].(map[string]any)
	rules, _ := route["rules"].([]any)
	for _, raw := range rules {
		if rule, ok := raw.(map[string]any); ok {
			if _, exists := rule[rootRouteSourcePortRejectMarker]; exists {
				return true
			}
		}
	}
	return false
}

// Replace source-port marker rules at their original indexes; legacy rules keep their order.
func materializeRootRouteSourcePortRejectRules(route map[string]any, spec *RootRouteSourcePortRejectRulesSpec) error {
	if err := validateRootRouteSourcePortRejectRules(spec, map[string]any{"route": route}); err != nil {
		return err
	}
	rules, _ := route["rules"].([]any)
	for index, raw := range rules {
		rule, ok := raw.(map[string]any)
		if !ok {
			continue
		}
		marker, exists, err := routeSourcePortRejectMarkerValue(rule)
		if err != nil {
			return err
		}
		if !exists {
			continue
		}
		text, _ := marker["sourcePortText"].(string)
		ports, err := parseRootPortRejectPorts(text)
		if err != nil {
			return err
		}
		rules[index] = map[string]any{"source_port": ports, "action": "reject"}
	}
	route["rules"] = rules
	return nil
}
