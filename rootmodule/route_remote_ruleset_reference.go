// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package module

import (
	"errors"
	"math"
)

const rootRemoteRuleSetReferenceMarker = "_wanbox_root_remote_rule_set_reference_v47"

func migratedRootRemoteRuleSetTags(config map[string]any) map[string]bool {
	tags := map[string]bool{}
	route, _ := config["route"].(map[string]any)
	entries, _ := route["rule_set"].([]any)
	for _, raw := range entries {
		item, ok := raw.(map[string]any)
		if !ok {
			continue
		}
		marker, ok := item[rootRemoteRuleSetMarker].(map[string]any)
		if !ok {
			continue
		}
		tag, ok := marker["tag"].(string)
		if ok && tag != "" {
			tags[tag] = true
		}
	}
	return tags
}

func inspectRootRemoteRuleSetRule(value any, tags map[string]bool, refs map[string]bool, hasRegex *bool, invalid *bool) {
	switch item := value.(type) {
	case map[string]any:
		for key, child := range item {
			if key == "domain_regex" {
				*hasRegex = true
			}
			if key == "rule_set" {
				values, ok := child.([]any)
				if !ok || len(values) == 0 {
					*invalid = true
					continue
				}
				for _, rawTag := range values {
					tag, ok := rawTag.(string)
					if !ok || tag == "" || !tags[tag] {
						*invalid = true
						continue
					}
					refs[tag] = true
				}
				continue
			}
			inspectRootRemoteRuleSetRule(child, tags, refs, hasRegex, invalid)
		}
	case []any:
		for _, child := range item {
			inspectRootRemoteRuleSetRule(child, tags, refs, hasRegex, invalid)
		}
	}
}

func validateRootRemoteRuleSetRuleArray(raw any, tags map[string]bool) error {
	entries, ok := raw.([]any)
	if !ok {
		return nil
	}
	for index, entry := range entries {
		item, ok := entry.(map[string]any)
		if !ok {
			continue
		}
		value, exists := item[rootRemoteRuleSetReferenceMarker]
		if !exists {
			continue
		}
		marker, ok := value.(map[string]any)
		if !ok || len(item) != 1 || len(marker) != 2 {
			return errors.New("root_remote_rule_set_reference_invalid")
		}
		markerIndex, ok := marker["index"].(float64)
		rule, ruleOK := marker["rule"].(map[string]any)
		if !ok || math.IsNaN(markerIndex) || math.IsInf(markerIndex, 0) || math.Trunc(markerIndex) != markerIndex || markerIndex != float64(index) || !ruleOK {
			return errors.New("root_remote_rule_set_reference_invalid")
		}
		refs := map[string]bool{}
		hasRegex, invalid := false, false
		inspectRootRemoteRuleSetRule(rule, tags, refs, &hasRegex, &invalid)
		if invalid || hasRegex || len(refs) == 0 {
			return errors.New("root_remote_rule_set_reference_invalid")
		}
	}
	return nil
}

func validateRootRemoteRuleSetReferences(config map[string]any) error {
	if !hasRootRemoteRuleSetReferenceMarker(config) {
		return nil
	}
	tags := migratedRootRemoteRuleSetTags(config)
	if len(tags) == 0 {
		return errors.New("root_remote_rule_set_reference_invalid")
	}
	route, _ := config["route"].(map[string]any)
	if err := validateRootRemoteRuleSetRuleArray(route["rules"], tags); err != nil {
		return err
	}
	dns, _ := config["dns"].(map[string]any)
	if err := validateRootRemoteRuleSetRuleArray(dns["rules"], tags); err != nil {
		return err
	}
	return nil
}

func hasRootRemoteRuleSetReferenceMarker(config map[string]any) bool {
	for _, object := range []map[string]any{nestedMap(config, "route"), nestedMap(config, "dns")} {
		if object == nil {
			continue
		}
		entries, _ := object["rules"].([]any)
		for _, raw := range entries {
			if item, ok := raw.(map[string]any); ok {
				if _, exists := item[rootRemoteRuleSetReferenceMarker]; exists {
					return true
				}
			}
		}
	}
	return false
}

func materializeRootRemoteRuleSetReferenceArray(raw any) error {
	entries, ok := raw.([]any)
	if !ok {
		return nil
	}
	for index, entry := range entries {
		item, ok := entry.(map[string]any)
		if !ok {
			continue
		}
		value, exists := item[rootRemoteRuleSetReferenceMarker]
		if !exists {
			continue
		}
		marker, ok := value.(map[string]any)
		if !ok || len(item) != 1 || len(marker) != 2 {
			return errors.New("root_remote_rule_set_reference_invalid")
		}
		rule, ok := marker["rule"].(map[string]any)
		if !ok {
			return errors.New("root_remote_rule_set_reference_invalid")
		}
		entries[index] = rule
	}
	return nil
}

func materializeRootRemoteRuleSetReferences(config map[string]any) error {
	route, _ := config["route"].(map[string]any)
	if route != nil {
		if err := materializeRootRemoteRuleSetReferenceArray(route["rules"]); err != nil {
			return err
		}
	}
	dns, _ := config["dns"].(map[string]any)
	if dns != nil {
		if err := materializeRootRemoteRuleSetReferenceArray(dns["rules"]); err != nil {
			return err
		}
	}
	return nil
}

func nestedMap(root map[string]any, key string) map[string]any {
	value, _ := root[key].(map[string]any)
	return value
}
