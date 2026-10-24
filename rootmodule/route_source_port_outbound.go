// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package module

import (
	"errors"
	"math"
	"strings"
)

const rootSourcePortOutboundMarker = "_wanbox_root_route_source_port_outbound_v63"
const maxRootSourcePortOutboundRuleID int64 = 9_007_199_254_740_991

func sourcePortOutboundMarkerValue(rule map[string]any) (map[string]any, bool, error) {
	value, exists := rule[rootSourcePortOutboundMarker]
	if !exists {
		return nil, false, nil
	}
	marker, ok := value.(map[string]any)
	if !ok || len(rule) != 1 || len(marker) != 4 {
		return nil, true, errors.New("root_source_port_outbound_invalid")
	}
	for _, key := range []string{"index", "ruleId", "sourcePortText", "outbound"} {
		if _, ok := marker[key]; !ok {
			return nil, true, errors.New("root_source_port_outbound_invalid")
		}
	}
	return marker, true, nil
}

func hasRootSourcePortOutboundMarker(config map[string]any) bool {
	rules, _ := nestedMap(config, "route")["rules"].([]any)
	for _, raw := range rules {
		if rule, ok := raw.(map[string]any); ok {
			if _, exists := rule[rootSourcePortOutboundMarker]; exists {
				return true
			}
		}
	}
	return false
}

func validateRootSourcePortOutbound(config map[string]any, snapshot Snapshot) error {
	rules, _ := nestedMap(config, "route")["rules"].([]any)
	seen := map[int64]bool{}
	for index, raw := range rules {
		rule, ok := raw.(map[string]any)
		if !ok {
			continue
		}
		marker, exists, err := sourcePortOutboundMarkerValue(rule)
		if err != nil {
			return err
		}
		if !exists {
			continue
		}
		idx, idxOK := marker["index"].(float64)
		id, idOK := marker["ruleId"].(float64)
		sourcePortText, sourceOK := marker["sourcePortText"].(string)
		outbound, outboundOK := marker["outbound"].(string)
		if !idxOK || math.IsNaN(idx) || math.IsInf(idx, 0) || math.Trunc(idx) != idx || idx != float64(index) ||
			!idOK || math.IsNaN(id) || math.IsInf(id, 0) || math.Trunc(id) != id || id < 1 || id > float64(maxRootSourcePortOutboundRuleID) || seen[int64(id)] ||
			!sourceOK || !outboundOK || len(outbound) == 0 || outbound == "block" || len(outbound) > 256 || strings.ContainsAny(outbound, "\\/\x00\r\n") {
			return errors.New("root_source_port_outbound_invalid")
		}
		if _, err := parseRootPortRejectPorts(sourcePortText); err != nil {
			return errors.New("root_source_port_outbound_invalid")
		}
		if !hasRootOutboundTag(config, snapshot, outbound) {
			return errors.New("root_source_port_outbound_unknown_outbound")
		}
		seen[int64(id)] = true
	}
	return nil
}

func materializeRootSourcePortOutbound(config map[string]any) error {
	route := nestedMap(config, "route")
	rules, _ := route["rules"].([]any)
	for index, raw := range rules {
		rule, ok := raw.(map[string]any)
		if !ok {
			continue
		}
		marker, exists, err := sourcePortOutboundMarkerValue(rule)
		if err != nil {
			return err
		}
		if !exists {
			continue
		}
		ports, err := parseRootPortRejectPorts(marker["sourcePortText"].(string))
		if err != nil {
			return errors.New("root_source_port_outbound_invalid")
		}
		rules[index] = map[string]any{"source_port": ports, "outbound": marker["outbound"].(string)}
	}
	route["rules"] = rules
	config["route"] = route
	return nil
}
