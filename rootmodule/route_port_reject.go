// @author 雾晚
package module

import (
	"errors"
	"math"
	"strconv"
	"strings"
)

const rootPortRejectRouteMarker = "_wanbox_root_route_port_reject_v30"
const maxRootPortRejectRuleID int64 = 9_007_199_254_740_991

type RootRoutePortRejectRulesSpec struct {
	Rules []RootRoutePortRejectRuleSpec `json:"rules"`
}

type RootRoutePortRejectRuleSpec struct {
	RuleID   int64  `json:"ruleId"`
	PortText string `json:"portText"`
}

// parseRootPortRejectPorts mirrors Kotlin's comma/newline split, trimming and
// empty-token filtering. It deliberately accepts only single decimal ports.
func parseRootPortRejectPorts(raw string) ([]int, error) {
	if len(raw) > 4096 {
		return nil, errors.New("root_route_port_reject_invalid")
	}
	tokens := strings.FieldsFunc(raw, func(r rune) bool { return r == ',' || r == '\n' })
	if len(tokens) == 0 || len(tokens) > 256 {
		return nil, errors.New("root_route_port_reject_invalid")
	}
	ports := make([]int, 0, len(tokens))
	for _, token := range tokens {
		token = strings.TrimSpace(token)
		if token == "" {
			continue
		}
		for _, r := range token {
			if r < '0' || r > '9' {
				return nil, errors.New("root_route_port_reject_invalid")
			}
		}
		port, err := strconv.Atoi(token)
		if err != nil || port < 1 || port > 65535 {
			return nil, errors.New("root_route_port_reject_invalid")
		}
		ports = append(ports, port)
	}
	if len(ports) == 0 {
		return nil, errors.New("root_route_port_reject_invalid")
	}
	return ports, nil
}

func routePortRejectMarkerValue(rule map[string]any) (map[string]any, bool, error) {
	value, exists := rule[rootPortRejectRouteMarker]
	if !exists {
		return nil, false, nil
	}
	if len(rule) != 1 {
		return nil, true, errors.New("root_route_port_reject_invalid")
	}
	marker, ok := value.(map[string]any)
	if !ok || len(marker) != 2 {
		return nil, true, errors.New("root_route_port_reject_invalid")
	}
	if _, ok := marker["ruleId"]; !ok {
		return nil, true, errors.New("root_route_port_reject_invalid")
	}
	if _, ok := marker["portText"]; !ok {
		return nil, true, errors.New("root_route_port_reject_invalid")
	}
	return marker, true, nil
}

func validateRootRoutePortRejectRules(spec *RootRoutePortRejectRulesSpec, config map[string]any) error {
	if spec == nil || len(spec.Rules) == 0 || len(spec.Rules) > 4096 {
		return errors.New("root_route_port_reject_invalid")
	}
	seenIDs := make(map[int64]struct{}, len(spec.Rules))
	for _, item := range spec.Rules {
		if item.RuleID < 1 || item.RuleID > maxRootPortRejectRuleID {
			return errors.New("root_route_port_reject_invalid")
		}
		if _, exists := seenIDs[item.RuleID]; exists {
			return errors.New("root_route_port_reject_invalid")
		}
		seenIDs[item.RuleID] = struct{}{}
		if _, err := parseRootPortRejectPorts(item.PortText); err != nil {
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
		marker, exists, err := routePortRejectMarkerValue(rule)
		if err != nil {
			return err
		}
		if !exists {
			continue
		}
		if found >= len(spec.Rules) {
			return errors.New("root_route_port_reject_invalid")
		}
		id, ok := marker["ruleId"].(float64)
		if !ok || math.IsNaN(id) || math.IsInf(id, 0) || math.Trunc(id) != id || id < 1 || id > float64(maxRootPortRejectRuleID) {
			return errors.New("root_route_port_reject_invalid")
		}
		portText, ok := marker["portText"].(string)
		if !ok {
			return errors.New("root_route_port_reject_invalid")
		}
		if _, err := parseRootPortRejectPorts(portText); err != nil {
			return err
		}
		expected := spec.Rules[found]
		if int64(id) != expected.RuleID || portText != expected.PortText {
			return errors.New("root_route_port_reject_invalid")
		}
		found++
	}
	if found != len(spec.Rules) {
		return errors.New("root_route_port_reject_invalid")
	}
	return nil
}

func hasRootRoutePortRejectMarker(config map[string]any) bool {
	route, _ := config["route"].(map[string]any)
	rules, _ := route["rules"].([]any)
	for _, raw := range rules {
		if rule, ok := raw.(map[string]any); ok {
			if _, exists := rule[rootPortRejectRouteMarker]; exists {
				return true
			}
		}
	}
	return false
}

// materializeRootRoutePortRejectRules replaces the raw builder markers in place,
// so all earlier/later legacy rules retain their exact ordering.
func materializeRootRoutePortRejectRules(route map[string]any, spec *RootRoutePortRejectRulesSpec, config map[string]any) error {
	if err := validateRootRoutePortRejectRules(spec, map[string]any{"route": route}); err != nil {
		return err
	}
	rules, _ := route["rules"].([]any)
	for index, raw := range rules {
		rule, ok := raw.(map[string]any)
		if !ok {
			continue
		}
		marker, exists, err := routePortRejectMarkerValue(rule)
		if err != nil {
			return err
		}
		if !exists {
			continue
		}
		text, _ := marker["portText"].(string)
		ports, err := parseRootPortRejectPorts(text)
		if err != nil {
			return err
		}
		rules[index] = map[string]any{"port": ports, "action": "reject"}
	}
	route["rules"] = rules
	config["route"] = route
	return nil
}
