// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package module

import (
	"errors"
	"math"
	"strings"
)

const rootPortOutboundMarker = "_wanbox_root_route_port_outbound_v82"
const maxRootPortOutboundRuleID int64 = 9_007_199_254_740_991

func portOutboundMarkerValue(rule map[string]any) (map[string]any, bool, error) {
	value, exists := rule[rootPortOutboundMarker]
	if !exists {
		return nil, false, nil
	}
	marker, ok := value.(map[string]any)
	if !ok || len(rule) != 1 || len(marker) != 4 {
		return nil, true, errors.New("root_port_outbound_invalid")
	}
	for _, key := range []string{"index", "ruleId", "portText", "outbound"} {
		if _, ok := marker[key]; !ok {
			return nil, true, errors.New("root_port_outbound_invalid")
		}
	}
	return marker, true, nil
}

func hasRootPortOutboundMarker(config map[string]any) bool {
	rules, _ := nestedMap(config, "route")["rules"].([]any)
	for _, raw := range rules {
		if rule, ok := raw.(map[string]any); ok {
			if _, exists := rule[rootPortOutboundMarker]; exists {
				return true
			}
		}
	}
	return false
}

func validateRootPortOutbound(config map[string]any, snapshot Snapshot) error {
	rules, _ := nestedMap(config, "route")["rules"].([]any)
	seen := map[int64]bool{}
	for index, raw := range rules {
		rule, ok := raw.(map[string]any)
		if !ok {
			continue
		}
		marker, exists, err := portOutboundMarkerValue(rule)
		if err != nil {
			return err
		}
		if !exists {
			continue
		}
		idx, idxOK := marker["index"].(float64)
		id, idOK := marker["ruleId"].(float64)
		portText, portOK := marker["portText"].(string)
		outbound, outboundOK := marker["outbound"].(string)
		if !idxOK || math.IsNaN(idx) || math.IsInf(idx, 0) || math.Trunc(idx) != idx || idx != float64(index) ||
			!idOK || math.IsNaN(id) || math.IsInf(id, 0) || math.Trunc(id) != id || id < 1 || id > float64(maxRootPortOutboundRuleID) || seen[int64(id)] ||
			!portOK || !outboundOK || len(outbound) == 0 || outbound == "block" || len(outbound) > 256 || strings.ContainsAny(outbound, "\\/\x00\r\n") ||
			!validRootRouteNetworkOutboundTag(outbound) {
			return errors.New("root_port_outbound_invalid")
		}
		if _, err := parseRootPortRejectPorts(portText); err != nil {
			return errors.New("root_port_outbound_invalid")
		}
		if !hasRootOutboundTag(config, snapshot, outbound) {
			return errors.New("root_port_outbound_unknown_outbound")
		}
		seen[int64(id)] = true
	}
	return nil
}

func materializeRootPortOutbound(config map[string]any) error {
	route := nestedMap(config, "route")
	rules, _ := route["rules"].([]any)
	for index, raw := range rules {
		rule, ok := raw.(map[string]any)
		if !ok {
			continue
		}
		marker, exists, err := portOutboundMarkerValue(rule)
		if err != nil {
			return err
		}
		if !exists {
			continue
		}
		idx, idxOK := marker["index"].(float64)
		portText, portOK := marker["portText"].(string)
		outbound, outboundOK := marker["outbound"].(string)
		if !idxOK || math.IsNaN(idx) || math.IsInf(idx, 0) || math.Trunc(idx) != idx || !portOK || !outboundOK || outbound == "" {
			return errors.New("root_port_outbound_invalid")
		}
		ports, err := parseRootPortRejectPorts(portText)
		if err != nil {
			return errors.New("root_port_outbound_invalid")
		}
		rules[index] = map[string]any{"port": ports, "outbound": outbound}
	}
	route["rules"] = rules
	config["route"] = route
	return nil
}
