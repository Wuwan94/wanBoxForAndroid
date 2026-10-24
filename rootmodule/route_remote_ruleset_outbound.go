// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package module

import (
	"errors"
	"strings"
)

const rootRemoteRuleSetOutboundRouteMarker = "_wanbox_root_remote_ruleset_outbound_v54"

func hasRootRemoteRuleSetOutboundRouteMarker(config map[string]any) bool {
	rules, _ := nestedMap(config, "route")["rules"].([]any)
	for _, raw := range rules {
		if rule, ok := raw.(map[string]any); ok {
			if _, exists := rule[rootRemoteRuleSetOutboundRouteMarker]; exists {
				return true
			}
		}
	}
	return false
}

func validateRootRemoteRuleSetOutboundRoutes(config map[string]any, snapshot Snapshot) error {
	route := nestedMap(config, "route")
	rules, ok := route["rules"].([]any)
	if !ok {
		return errors.New("root_remote_ruleset_outbound_invalid")
	}
	migratedTags := migratedRootRemoteRuleSetTags(config)
	for _, raw := range rules {
		rule, ok := raw.(map[string]any)
		if !ok {
			continue
		}
		value, exists := rule[rootRemoteRuleSetOutboundRouteMarker]
		if !exists {
			continue
		}
		marker, ok := value.(map[string]any)
		tags, tagsOK := marker["tags"].([]any)
		outbound, outboundOK := marker["outbound"].(string)
		_, invertOK := marker["invert"].(bool)
		if !ok || len(rule) != 1 || len(marker) != 3 || !tagsOK || len(tags) == 0 || len(tags) > 256 ||
			!outboundOK || outbound == "" || len(outbound) > 256 || strings.ContainsAny(outbound, "\\/\x00\r\n") || !invertOK ||
			!validRootRouteNetworkOutboundTag(outbound) || !hasRootOutboundTag(config, snapshot, outbound) {
			return errors.New("root_remote_ruleset_outbound_invalid")
		}
		seenTags := map[string]bool{}
		for _, value := range tags {
			tag, ok := value.(string)
			if !ok || !validRootRouteNetworkOutboundTag(tag) || seenTags[tag] || !migratedTags[tag] {
				return errors.New("root_remote_ruleset_outbound_invalid")
			}
			seenTags[tag] = true
		}
	}
	return nil
}

func materializeRootRemoteRuleSetOutboundRoutes(config map[string]any) error {
	route := nestedMap(config, "route")
	rules, ok := route["rules"].([]any)
	if !ok {
		return errors.New("root_remote_ruleset_outbound_invalid")
	}
	for index, raw := range rules {
		rule, ok := raw.(map[string]any)
		if !ok {
			continue
		}
		value, exists := rule[rootRemoteRuleSetOutboundRouteMarker]
		if !exists {
			continue
		}
		marker := value.(map[string]any)
		tags := marker["tags"].([]any)
		generated := map[string]any{"rule_set": tags, "outbound": marker["outbound"].(string)}
		if marker["invert"].(bool) {
			generated["invert"] = true
		}
		rules[index] = generated
	}
	route["rules"] = rules
	config["route"] = route
	return nil
}
