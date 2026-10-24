// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package module

import (
	"errors"
	"math"
)

const rootRouteRawRuleMarker = "_wanbox_root_route_raw_rule_v48"

func inspectRootRawRule(value any, hasRegex *bool) {
	switch item := value.(type) {
	case map[string]any:
		for key, child := range item {
			if key == "domain_regex" {
				*hasRegex = true
			}
			inspectRootRawRule(child, hasRegex)
		}
	case []any:
		for _, child := range item {
			inspectRootRawRule(child, hasRegex)
		}
	}
}

func validateRootRawRuleArray(raw any) error {
	entries, ok := raw.([]any)
	if !ok {
		return nil
	}
	for index, entry := range entries {
		item, ok := entry.(map[string]any)
		if !ok {
			continue
		}
		value, exists := item[rootRouteRawRuleMarker]
		if !exists {
			continue
		}
		marker, ok := value.(map[string]any)
		if !ok || len(item) != 1 || len(marker) != 2 {
			return errors.New("root_route_raw_rule_invalid")
		}
		markerIndex, ok := marker["index"].(float64)
		rule, ruleOK := marker["rule"].(map[string]any)
		var hasRegex bool
		if !ok || math.IsNaN(markerIndex) || math.IsInf(markerIndex, 0) || math.Trunc(markerIndex) != markerIndex || markerIndex != float64(index) || !ruleOK {
			return errors.New("root_route_raw_rule_invalid")
		}
		inspectRootRawRule(rule, &hasRegex)
		if hasRegex {
			return errors.New("root_route_raw_rule_invalid")
		}
	}
	return nil
}

func hasRootRawRuleMarker(config map[string]any) bool {
	for _, object := range []map[string]any{nestedMap(config, "route"), nestedMap(config, "dns")} {
		if object == nil {
			continue
		}
		entries, _ := object["rules"].([]any)
		for _, raw := range entries {
			if item, ok := raw.(map[string]any); ok {
				if _, exists := item[rootRouteRawRuleMarker]; exists {
					return true
				}
			}
		}
	}
	return false
}

func validateRootRawRules(config map[string]any) error {
	if err := validateRootRawRuleArray(nestedMap(config, "route")["rules"]); err != nil {
		return err
	}
	return validateRootRawRuleArray(nestedMap(config, "dns")["rules"])
}

func materializeRootRawRuleArray(raw any) error {
	entries, ok := raw.([]any)
	if !ok {
		return nil
	}
	for index, entry := range entries {
		item, ok := entry.(map[string]any)
		if !ok {
			continue
		}
		value, exists := item[rootRouteRawRuleMarker]
		if !exists {
			continue
		}
		marker, ok := value.(map[string]any)
		if !ok || len(item) != 1 || len(marker) != 2 {
			return errors.New("root_route_raw_rule_invalid")
		}
		// Projectors before this one may insert rules after snapshot validation. The marker's
		// current slot is authoritative; never use the stale original index to reorder output.
		rule, ok := marker["rule"].(map[string]any)
		if !ok {
			return errors.New("root_route_raw_rule_invalid")
		}
		entries[index] = rule
	}
	return nil
}

func materializeRootRawRules(config map[string]any) error {
	if err := materializeRootRawRuleArray(nestedMap(config, "route")["rules"]); err != nil {
		return err
	}
	return materializeRootRawRuleArray(nestedMap(config, "dns")["rules"])
}
