// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package module

import (
	"errors"
	"math"
	"strings"
)

const rootRemoteRuleSetIPRejectRouteMarker = "_wanbox_root_remote_ruleset_ip_reject_v73"

func rootRemoteRuleSetIPRejectRouteValue(rule map[string]any) (map[string]any, bool, error) {
	value, exists := rule[rootRemoteRuleSetIPRejectRouteMarker]
	if !exists {
		return nil, false, nil
	}
	marker, ok := value.(map[string]any)
	if !ok || len(rule) != 1 || len(marker) != 4 {
		return nil, true, errors.New("root_remote_ruleset_ip_reject_invalid")
	}
	for _, key := range []string{"index", "tags", "cidrs", "invert"} {
		if _, ok := marker[key]; !ok {
			return nil, true, errors.New("root_remote_ruleset_ip_reject_invalid")
		}
	}
	return marker, true, nil
}

func hasRootRemoteRuleSetIPRejectRouteMarker(config map[string]any) bool {
	rules, _ := nestedMap(config, "route")["rules"].([]any)
	for _, raw := range rules {
		if rule, ok := raw.(map[string]any); ok {
			if _, exists := rule[rootRemoteRuleSetIPRejectRouteMarker]; exists {
				return true
			}
		}
	}
	return false
}

func validateRootRemoteRuleSetIPRejectRoutes(config map[string]any, snapshot Snapshot) error {
	rules, ok := nestedMap(config, "route")["rules"].([]any)
	if !ok {
		return errors.New("root_remote_ruleset_ip_reject_invalid")
	}
	migrated := migratedRootRemoteRuleSetTags(config)
	for index, raw := range rules {
		rule, ok := raw.(map[string]any)
		if !ok {
			continue
		}
		marker, exists, err := rootRemoteRuleSetIPRejectRouteValue(rule)
		if err != nil {
			return err
		}
		if !exists {
			continue
		}
		idx, idxOK := marker["index"].(float64)
		tags, tagsOK := marker["tags"].([]any)
		cidrs, cidrsOK := marker["cidrs"].([]any)
		_, invertOK := marker["invert"].(bool)
		if !idxOK || math.IsNaN(idx) || math.IsInf(idx, 0) || math.Trunc(idx) != idx || idx != float64(index) ||
			!tagsOK || len(tags) == 0 || len(tags) > 256 || !cidrsOK || len(cidrs) == 0 || len(cidrs) > 256 || !invertOK ||
			snapshot.RootInbound == nil || snapshot.RootInbound.Backend != "tun" {
			return errors.New("root_remote_ruleset_ip_reject_invalid")
		}
		seenTags := map[string]bool{}
		for _, value := range tags {
			tag, ok := value.(string)
			if !ok || !validRootRouteNetworkOutboundTag(tag) || seenTags[tag] || !migrated[tag] {
				return errors.New("root_remote_ruleset_ip_reject_invalid")
			}
			seenTags[tag] = true
		}
		parts := make([]string, 0, len(cidrs))
		for _, value := range cidrs {
			cidr, ok := value.(string)
			if !ok || strings.TrimSpace(cidr) != cidr || cidr == "" || strings.ContainsAny(cidr, ",\n") {
				return errors.New("root_remote_ruleset_ip_reject_invalid")
			}
			parts = append(parts, cidr)
		}
		if _, err := parseRootIPOutboundCIDRs(strings.Join(parts, "\n")); err != nil {
			return errors.New("root_remote_ruleset_ip_reject_invalid")
		}
	}
	return nil
}

func materializeRootRemoteRuleSetIPRejectRoutes(config map[string]any) error {
	route := nestedMap(config, "route")
	rules, ok := route["rules"].([]any)
	if !ok {
		return errors.New("root_remote_ruleset_ip_reject_invalid")
	}
	for index, raw := range rules {
		rule, ok := raw.(map[string]any)
		if !ok {
			continue
		}
		marker, exists, err := rootRemoteRuleSetIPRejectRouteValue(rule)
		if err != nil {
			return err
		}
		if !exists {
			continue
		}
		idx, idxOK := marker["index"].(float64)
		tags, tagsOK := marker["tags"].([]any)
		cidrs, cidrsOK := marker["cidrs"].([]any)
		invert, invertOK := marker["invert"].(bool)
		if !idxOK || idx != float64(index) || !tagsOK || !cidrsOK || !invertOK {
			return errors.New("root_remote_ruleset_ip_reject_invalid")
		}
		generated := map[string]any{"rule_set": tags, "ip_cidr": cidrs, "action": "reject"}
		if invert {
			generated["invert"] = true
		}
		rules[index] = generated
	}
	route["rules"] = rules
	config["route"] = route
	return nil
}
