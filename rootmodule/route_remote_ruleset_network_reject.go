// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package module

import (
	"errors"
	"math"
)

const rootRemoteRuleSetNetworkRejectRouteMarker = "_wanbox_root_remote_ruleset_network_reject_v72"

func rootRemoteRuleSetNetworkRejectRouteValue(rule map[string]any) (map[string]any, bool, error) {
	value, exists := rule[rootRemoteRuleSetNetworkRejectRouteMarker]
	if !exists {
		return nil, false, nil
	}
	marker, ok := value.(map[string]any)
	if !ok || len(rule) != 1 || len(marker) != 4 {
		return nil, true, errors.New("root_remote_ruleset_network_reject_route_invalid")
	}
	for _, key := range []string{"index", "tags", "networks", "invert"} {
		if _, ok := marker[key]; !ok {
			return nil, true, errors.New("root_remote_ruleset_network_reject_route_invalid")
		}
	}
	return marker, true, nil
}

func hasRootRemoteRuleSetNetworkRejectRouteMarker(config map[string]any) bool {
	rules, _ := nestedMap(config, "route")["rules"].([]any)
	for _, raw := range rules {
		if rule, ok := raw.(map[string]any); ok {
			if _, exists := rule[rootRemoteRuleSetNetworkRejectRouteMarker]; exists {
				return true
			}
		}
	}
	return false
}

func validateRootRemoteRuleSetNetworkRejectRoutes(config map[string]any, snapshot Snapshot) error {
	rules, ok := nestedMap(config, "route")["rules"].([]any)
	if !ok {
		return errors.New("root_remote_ruleset_network_reject_route_invalid")
	}
	migrated := migratedRootRemoteRuleSetTags(config)
	for index, raw := range rules {
		rule, ok := raw.(map[string]any)
		if !ok {
			continue
		}
		marker, exists, err := rootRemoteRuleSetNetworkRejectRouteValue(rule)
		if err != nil {
			return err
		}
		if !exists {
			continue
		}
		idx, idxOK := marker["index"].(float64)
		tags, tagsOK := marker["tags"].([]any)
		networks, networksOK := marker["networks"].([]any)
		_, invertOK := marker["invert"].(bool)
		if !idxOK || math.IsNaN(idx) || math.IsInf(idx, 0) || math.Trunc(idx) != idx || idx != float64(index) ||
			!tagsOK || len(tags) == 0 || len(tags) > 256 || !networksOK || len(networks) == 0 || len(networks) > 2 || !invertOK ||
			snapshot.RootInbound == nil || snapshot.RootInbound.Backend != "tun" {
			return errors.New("root_remote_ruleset_network_reject_route_invalid")
		}
		seenTags := map[string]bool{}
		for _, value := range tags {
			tag, ok := value.(string)
			if !ok || !validRootRouteNetworkOutboundTag(tag) || seenTags[tag] || !migrated[tag] {
				return errors.New("root_remote_ruleset_network_reject_route_invalid")
			}
			seenTags[tag] = true
		}
		seenNetworks := map[string]bool{}
		for _, value := range networks {
			network, ok := value.(string)
			if !ok || (network != "tcp" && network != "udp") || seenNetworks[network] {
				return errors.New("root_remote_ruleset_network_reject_route_invalid")
			}
			seenNetworks[network] = true
		}
	}
	return nil
}

func materializeRootRemoteRuleSetNetworkRejectRoutes(config map[string]any) error {
	route := nestedMap(config, "route")
	rules, ok := route["rules"].([]any)
	if !ok {
		return errors.New("root_remote_ruleset_network_reject_route_invalid")
	}
	for index, raw := range rules {
		rule, ok := raw.(map[string]any)
		if !ok {
			continue
		}
		marker, exists, err := rootRemoteRuleSetNetworkRejectRouteValue(rule)
		if err != nil {
			return err
		}
		if !exists {
			continue
		}
		idx, idxOK := marker["index"].(float64)
		tags, tagsOK := marker["tags"].([]any)
		networks, networksOK := marker["networks"].([]any)
		invert, invertOK := marker["invert"].(bool)
		if !idxOK || idx != float64(index) || !tagsOK || !networksOK || !invertOK {
			return errors.New("root_remote_ruleset_network_reject_route_invalid")
		}
		generated := map[string]any{"rule_set": tags, "network": networks, "action": "reject"}
		if invert {
			generated["invert"] = true
		}
		rules[index] = generated
	}
	route["rules"] = rules
	config["route"] = route
	return nil
}
