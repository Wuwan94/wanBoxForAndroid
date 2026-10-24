// @author 雾晚
package module

import (
	"errors"
	"math"
	"strings"
	"unicode/utf16"
)

const rootRouteNetworkRejectMarker = "_wanbox_root_route_network_reject_v31"
const maxRootRouteNetworkRejectRuleID int64 = 9_007_199_254_740_991

type RootRouteNetworkRejectRulesSpec struct {
	Rules []RootRouteNetworkRejectRuleSpec `json:"rules"`
}

type RootRouteNetworkRejectRuleSpec struct {
	RuleID      int64  `json:"ruleId"`
	NetworkText string `json:"networkText"`
}

// parseRootRouteNetworkRejectNetworks mirrors listByLineOrComma and accepts
// only the standard network values supported by this complete migration slice.
// It preserves order and duplicates exactly as the App serializer does.
func parseRootRouteNetworkRejectNetworks(raw string) ([]string, error) {
	// Kotlin String.length counts UTF-16 code units; use the same bound here.
	if len(utf16.Encode([]rune(raw))) > 4096 {
		return nil, errors.New("root_route_network_reject_invalid")
	}
	tokens := strings.FieldsFunc(raw, func(r rune) bool { return r == ',' || r == '\n' })
	networks := make([]string, 0, len(tokens))
	for _, token := range tokens {
		token = strings.TrimSpace(token)
		if token == "" {
			continue
		}
		if len(networks) >= 256 || (token != "tcp" && token != "udp") {
			return nil, errors.New("root_route_network_reject_invalid")
		}
		networks = append(networks, token)
	}
	if len(networks) == 0 {
		return nil, errors.New("root_route_network_reject_invalid")
	}
	return networks, nil
}

func routeNetworkRejectMarkerValue(rule map[string]any) (map[string]any, bool, error) {
	value, exists := rule[rootRouteNetworkRejectMarker]
	if !exists {
		return nil, false, nil
	}
	if len(rule) != 1 {
		return nil, true, errors.New("root_route_network_reject_invalid")
	}
	marker, ok := value.(map[string]any)
	if !ok || len(marker) != 2 {
		return nil, true, errors.New("root_route_network_reject_invalid")
	}
	if _, ok := marker["ruleId"]; !ok {
		return nil, true, errors.New("root_route_network_reject_invalid")
	}
	if _, ok := marker["networkText"]; !ok {
		return nil, true, errors.New("root_route_network_reject_invalid")
	}
	return marker, true, nil
}

func validateRootRouteNetworkRejectRules(spec *RootRouteNetworkRejectRulesSpec, config map[string]any) error {
	if spec == nil || len(spec.Rules) == 0 || len(spec.Rules) > 4096 {
		return errors.New("root_route_network_reject_invalid")
	}
	seenIDs := make(map[int64]struct{}, len(spec.Rules))
	for _, item := range spec.Rules {
		if item.RuleID < 1 || item.RuleID > maxRootRouteNetworkRejectRuleID {
			return errors.New("root_route_network_reject_invalid")
		}
		if _, exists := seenIDs[item.RuleID]; exists {
			return errors.New("root_route_network_reject_invalid")
		}
		seenIDs[item.RuleID] = struct{}{}
		if _, err := parseRootRouteNetworkRejectNetworks(item.NetworkText); err != nil {
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
		marker, exists, err := routeNetworkRejectMarkerValue(rule)
		if err != nil {
			return err
		}
		if !exists {
			continue
		}
		if found >= len(spec.Rules) {
			return errors.New("root_route_network_reject_invalid")
		}
		id, ok := marker["ruleId"].(float64)
		if !ok || math.IsNaN(id) || math.IsInf(id, 0) || math.Trunc(id) != id || id < 1 || id > float64(maxRootRouteNetworkRejectRuleID) {
			return errors.New("root_route_network_reject_invalid")
		}
		text, ok := marker["networkText"].(string)
		if !ok {
			return errors.New("root_route_network_reject_invalid")
		}
		if _, err := parseRootRouteNetworkRejectNetworks(text); err != nil {
			return err
		}
		expected := spec.Rules[found]
		if int64(id) != expected.RuleID || text != expected.NetworkText {
			return errors.New("root_route_network_reject_invalid")
		}
		found++
	}
	if found != len(spec.Rules) {
		return errors.New("root_route_network_reject_invalid")
	}
	return nil
}

func hasRootRouteNetworkRejectMarker(config map[string]any) bool {
	route, _ := config["route"].(map[string]any)
	rules, _ := route["rules"].([]any)
	for _, raw := range rules {
		if rule, ok := raw.(map[string]any); ok {
			if _, exists := rule[rootRouteNetworkRejectMarker]; exists {
				return true
			}
		}
	}
	return false
}

// materializeRootRouteNetworkRejectRules replaces only typed input markers in place.
func materializeRootRouteNetworkRejectRules(route map[string]any, spec *RootRouteNetworkRejectRulesSpec, config map[string]any) error {
	if err := validateRootRouteNetworkRejectRules(spec, map[string]any{"route": route}); err != nil {
		return err
	}
	rules, _ := route["rules"].([]any)
	for index, raw := range rules {
		rule, ok := raw.(map[string]any)
		if !ok {
			continue
		}
		marker, exists, err := routeNetworkRejectMarkerValue(rule)
		if err != nil {
			return err
		}
		if !exists {
			continue
		}
		text, _ := marker["networkText"].(string)
		networks, err := parseRootRouteNetworkRejectNetworks(text)
		if err != nil {
			return err
		}
		rules[index] = map[string]any{"network": networks, "action": "reject"}
	}
	route["rules"] = rules
	config["route"] = route
	return nil
}
