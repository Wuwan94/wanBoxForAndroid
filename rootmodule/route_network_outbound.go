// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package module

import (
	"errors"
	"math"
	"strings"
	"unicode"
)

const rootRouteNetworkOutboundMarker = "_wanbox_root_route_network_outbound_v44"

type RootRouteNetworkOutboundRulesSpec struct {
	Rules []RootRouteNetworkOutboundRuleSpec `json:"rules"`
}

type RootRouteNetworkOutboundRuleSpec struct {
	Index    int      `json:"index"`
	Network  []string `json:"network"`
	Outbound string   `json:"outbound"`
}

func validRootRouteNetworkOutboundNetworks(networks []string) bool {
	if len(networks) == 0 || len(networks) > 256 {
		return false
	}
	for _, network := range networks {
		if network != "tcp" && network != "udp" {
			return false
		}
	}
	return true
}

func validRootRouteNetworkOutboundTag(tag string) bool {
	if tag == "" || len(tag) > 256 || strings.TrimSpace(tag) != tag || strings.ContainsAny(tag, "\\/\x00") {
		return false
	}
	for _, r := range tag {
		if unicode.IsControl(r) {
			return false
		}
	}
	return true
}

func validateRootRouteNetworkOutboundRules(spec *RootRouteNetworkOutboundRulesSpec, config map[string]any, snapshot Snapshot) error {
	if spec == nil || len(spec.Rules) == 0 || len(spec.Rules) > 4096 {
		return errors.New("root_route_network_outbound_invalid")
	}
	route, _ := config["route"].(map[string]any)
	rules, _ := route["rules"].([]any)
	found := 0
	for index, raw := range rules {
		rule, ok := raw.(map[string]any)
		if !ok {
			continue
		}
		value, exists := rule[rootRouteNetworkOutboundMarker]
		if !exists {
			continue
		}
		if len(rule) != 1 || found >= len(spec.Rules) {
			return errors.New("root_route_network_outbound_invalid")
		}
		marker, ok := value.(map[string]any)
		if !ok || len(marker) != 1 {
			return errors.New("root_route_network_outbound_invalid")
		}
		markerIndex, ok := marker["index"].(float64)
		item := spec.Rules[found]
		if !ok || math.IsNaN(markerIndex) || math.IsInf(markerIndex, 0) || math.Trunc(markerIndex) != markerIndex || markerIndex < 0 || markerIndex > 1<<20 ||
			item.Index != index || int(markerIndex) != index || !validRootRouteNetworkOutboundNetworks(item.Network) ||
			!validRootRouteNetworkOutboundTag(item.Outbound) || !hasRootOutboundTag(config, snapshot, item.Outbound) {
			return errors.New("root_route_network_outbound_invalid")
		}
		found++
	}
	if found != len(spec.Rules) {
		return errors.New("root_route_network_outbound_invalid")
	}
	return nil
}

func hasRootRouteNetworkOutboundMarker(config map[string]any) bool {
	route, _ := config["route"].(map[string]any)
	rules, _ := route["rules"].([]any)
	for _, raw := range rules {
		if rule, ok := raw.(map[string]any); ok {
			if _, exists := rule[rootRouteNetworkOutboundMarker]; exists {
				return true
			}
		}
	}
	return false
}

// Replace migration markers in place after all other Root insertions, preserving rule order.
func materializeRootRouteNetworkOutboundRules(route map[string]any, spec *RootRouteNetworkOutboundRulesSpec) error {
	if spec == nil || len(spec.Rules) == 0 || len(spec.Rules) > 4096 {
		return errors.New("root_route_network_outbound_invalid")
	}
	rules, _ := route["rules"].([]any)
	found := 0
	for index, raw := range rules {
		rule, ok := raw.(map[string]any)
		if !ok {
			continue
		}
		value, exists := rule[rootRouteNetworkOutboundMarker]
		if !exists {
			continue
		}
		if len(rule) != 1 || found >= len(spec.Rules) {
			return errors.New("root_route_network_outbound_invalid")
		}
		marker, ok := value.(map[string]any)
		if !ok || len(marker) != 1 {
			return errors.New("root_route_network_outbound_invalid")
		}
		markerIndex, ok := marker["index"].(float64)
		item := spec.Rules[found]
		if !ok || math.Trunc(markerIndex) != markerIndex || markerIndex != float64(item.Index) ||
			!validRootRouteNetworkOutboundNetworks(item.Network) || !validRootRouteNetworkOutboundTag(item.Outbound) {
			return errors.New("root_route_network_outbound_invalid")
		}
		rules[index] = map[string]any{"network": append([]string(nil), item.Network...), "outbound": item.Outbound}
		found++
	}
	if found != len(spec.Rules) {
		return errors.New("root_route_network_outbound_invalid")
	}
	route["rules"] = rules
	return nil
}
