// @author 雾晚
package module

import (
	"errors"
	"math"
)

const rootRouteSourceDestinationPortRejectMarker = "_wanbox_root_route_source_destination_port_reject_v38"
const maxRootRouteSourceDestinationPortRejectRuleID int64 = 9_007_199_254_740_991

type RootRouteSourceDestinationPortRejectRulesSpec struct {
	Rules []RootRouteSourceDestinationPortRejectRuleSpec `json:"rules"`
}

type RootRouteSourceDestinationPortRejectRuleSpec struct {
	RuleID         int64  `json:"ruleId"`
	SourcePortText string `json:"sourcePortText"`
	PortText       string `json:"portText"`
}

func routeSourceDestinationPortRejectMarkerValue(rule map[string]any) (map[string]any, bool, error) {
	value, exists := rule[rootRouteSourceDestinationPortRejectMarker]
	if !exists {
		return nil, false, nil
	}
	marker, ok := value.(map[string]any)
	if len(rule) != 1 || !ok || len(marker) != 3 {
		return nil, true, errors.New("root_route_source_destination_port_reject_invalid")
	}
	if _, ok = marker["ruleId"]; !ok {
		return nil, true, errors.New("root_route_source_destination_port_reject_invalid")
	}
	if _, ok = marker["sourcePortText"]; !ok {
		return nil, true, errors.New("root_route_source_destination_port_reject_invalid")
	}
	if _, ok = marker["portText"]; !ok {
		return nil, true, errors.New("root_route_source_destination_port_reject_invalid")
	}
	return marker, true, nil
}

func validateRootRouteSourceDestinationPortRejectRules(spec *RootRouteSourceDestinationPortRejectRulesSpec, config map[string]any) error {
	if spec == nil || len(spec.Rules) == 0 || len(spec.Rules) > 4096 {
		return errors.New("root_route_source_destination_port_reject_invalid")
	}
	seen := make(map[int64]struct{}, len(spec.Rules))
	for _, item := range spec.Rules {
		if item.RuleID < 1 || item.RuleID > maxRootRouteSourceDestinationPortRejectRuleID {
			return errors.New("root_route_source_destination_port_reject_invalid")
		}
		if _, exists := seen[item.RuleID]; exists {
			return errors.New("root_route_source_destination_port_reject_invalid")
		}
		seen[item.RuleID] = struct{}{}
		if _, err := parseRootPortRejectPorts(item.SourcePortText); err != nil {
			return errors.New("root_route_source_destination_port_reject_invalid")
		}
		if _, err := parseRootPortRejectPorts(item.PortText); err != nil {
			return errors.New("root_route_source_destination_port_reject_invalid")
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
		marker, exists, err := routeSourceDestinationPortRejectMarkerValue(rule)
		if err != nil {
			return err
		}
		if !exists {
			continue
		}
		if found >= len(spec.Rules) {
			return errors.New("root_route_source_destination_port_reject_invalid")
		}
		id, ok := marker["ruleId"].(float64)
		if !ok || math.IsNaN(id) || math.IsInf(id, 0) || math.Trunc(id) != id || id < 1 || id > float64(maxRootRouteSourceDestinationPortRejectRuleID) {
			return errors.New("root_route_source_destination_port_reject_invalid")
		}
		sourceText, ok := marker["sourcePortText"].(string)
		if !ok {
			return errors.New("root_route_source_destination_port_reject_invalid")
		}
		portText, ok := marker["portText"].(string)
		if !ok {
			return errors.New("root_route_source_destination_port_reject_invalid")
		}
		if _, err := parseRootPortRejectPorts(sourceText); err != nil {
			return errors.New("root_route_source_destination_port_reject_invalid")
		}
		if _, err := parseRootPortRejectPorts(portText); err != nil {
			return errors.New("root_route_source_destination_port_reject_invalid")
		}
		want := spec.Rules[found]
		if int64(id) != want.RuleID || sourceText != want.SourcePortText || portText != want.PortText {
			return errors.New("root_route_source_destination_port_reject_invalid")
		}
		found++
	}
	if found != len(spec.Rules) {
		return errors.New("root_route_source_destination_port_reject_invalid")
	}
	return nil
}

func hasRootRouteSourceDestinationPortRejectMarker(config map[string]any) bool {
	route, _ := config["route"].(map[string]any)
	rules, _ := route["rules"].([]any)
	for _, raw := range rules {
		if rule, ok := raw.(map[string]any); ok {
			if _, exists := rule[rootRouteSourceDestinationPortRejectMarker]; exists {
				return true
			}
		}
	}
	return false
}

// Replace source+destination-port marker rules at their original indexes; legacy rules keep their order.
func materializeRootRouteSourceDestinationPortRejectRules(route map[string]any, spec *RootRouteSourceDestinationPortRejectRulesSpec) error {
	if err := validateRootRouteSourceDestinationPortRejectRules(spec, map[string]any{"route": route}); err != nil {
		return err
	}
	rules, _ := route["rules"].([]any)
	for index, raw := range rules {
		rule, ok := raw.(map[string]any)
		if !ok {
			continue
		}
		marker, exists, err := routeSourceDestinationPortRejectMarkerValue(rule)
		if err != nil {
			return err
		}
		if !exists {
			continue
		}
		sourcePorts, err := parseRootPortRejectPorts(marker["sourcePortText"].(string))
		if err != nil {
			return err
		}
		destinationPorts, err := parseRootPortRejectPorts(marker["portText"].(string))
		if err != nil {
			return err
		}
		rules[index] = map[string]any{"source_port": sourcePorts, "port": destinationPorts, "action": "reject"}
	}
	route["rules"] = rules
	return nil
}
