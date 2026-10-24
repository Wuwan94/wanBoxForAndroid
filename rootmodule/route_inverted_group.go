// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package module

import (
	"errors"
	"math"
)

const rootRouteInvertedGroupMarker = "_wanbox_root_route_inverted_group_v49"

var rootRouteInvertedActionKeys = map[string]bool{
	"action": true, "outbound": true, "method": true, "no_drop": true,
	"strategy": true, "sniffer": true, "timeout": true,
	"override_address": true, "override_port": true,
}

func validRootInvertedGroupMarker(item map[string]any, index int) (map[string]any, bool, error) {
	value, exists := item[rootRouteInvertedGroupMarker]
	if !exists {
		return nil, false, nil
	}
	marker, ok := value.(map[string]any)
	if !ok || len(item) != 1 || len(marker) != 3 {
		return nil, true, errors.New("root_route_inverted_group_invalid")
	}
	idx, ok := marker["index"].(float64)
	rules, rulesOK := marker["rules"].([]any)
	action, actionOK := marker["action"].(map[string]any)
	if !ok || math.IsNaN(idx) || math.IsInf(idx, 0) || math.Trunc(idx) != idx || idx != float64(index) || !rulesOK || len(rules) < 2 || !actionOK {
		return nil, true, errors.New("root_route_inverted_group_invalid")
	}
	for key := range action {
		if !rootRouteInvertedActionKeys[key] {
			return nil, true, errors.New("root_route_inverted_group_invalid")
		}
	}
	for _, raw := range rules {
		child, ok := raw.(map[string]any)
		if !ok {
			return nil, true, errors.New("root_route_inverted_group_invalid")
		}
		for key := range child {
			if rootRouteInvertedActionKeys[key] || key == "invert" || key == "type" || key == "mode" {
				return nil, true, errors.New("root_route_inverted_group_invalid")
			}
		}
		var hasRegex bool
		inspectRootRawRule(child, &hasRegex)
		if hasRegex {
			return nil, true, errors.New("root_route_inverted_group_invalid")
		}
	}
	return marker, true, nil
}

func validateRootInvertedGroups(config map[string]any) error {
	route := nestedMap(config, "route")
	if route == nil {
		return nil
	}
	rules, ok := route["rules"].([]any)
	if !ok {
		return nil
	}
	for index, raw := range rules {
		item, ok := raw.(map[string]any)
		if !ok {
			continue
		}
		if _, _, err := validRootInvertedGroupMarker(item, index); err != nil {
			return err
		}
	}
	return nil
}

func hasRootInvertedGroupMarker(config map[string]any) bool {
	route := nestedMap(config, "route")
	rules, _ := route["rules"].([]any)
	for _, raw := range rules {
		if item, ok := raw.(map[string]any); ok {
			if _, exists := item[rootRouteInvertedGroupMarker]; exists {
				return true
			}
		}
	}
	return false
}

func materializeRootInvertedGroups(config map[string]any) error {
	route := nestedMap(config, "route")
	if route == nil {
		return nil
	}
	rules, ok := route["rules"].([]any)
	if !ok {
		return nil
	}
	for index, raw := range rules {
		item, ok := raw.(map[string]any)
		if !ok {
			continue
		}
		marker, exists, err := validRootInvertedGroupMarker(item, index)
		if err != nil {
			return err
		}
		if !exists {
			continue
		}
		group := map[string]any{"type": "logical", "mode": "or", "rules": marker["rules"], "invert": true}
		for key, value := range marker["action"].(map[string]any) {
			group[key] = value
		}
		rules[index] = group
	}
	route["rules"] = rules
	return nil
}
