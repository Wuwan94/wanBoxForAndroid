// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package module

import (
	"errors"
	"math"
	"strings"
)

const rootRemoteRuleSetNetworkOutboundRouteMarker = "_wanbox_root_remote_ruleset_network_outbound_v70"

func rootRemoteRuleSetNetworkOutboundMarker(rule map[string]any) (map[string]any, bool, error) {
	value, exists := rule[rootRemoteRuleSetNetworkOutboundRouteMarker]
	if !exists {
		return nil, false, nil
	}
	marker, ok := value.(map[string]any)
	if !ok || len(rule) != 1 || len(marker) != 5 {
		return nil, true, errors.New("root_remote_ruleset_network_outbound_invalid")
	}
	for _, key := range []string{"index", "tags", "networks", "outbound", "invert"} {
		if _, ok := marker[key]; !ok {
			return nil, true, errors.New("root_remote_ruleset_network_outbound_invalid")
		}
	}
	return marker, true, nil
}

func hasRootRemoteRuleSetNetworkOutboundRouteMarker(config map[string]any) bool {
	rules, _ := nestedMap(config, "route")["rules"].([]any)
	for _, raw := range rules {
		if rule, ok := raw.(map[string]any); ok {
			if _, exists := rule[rootRemoteRuleSetNetworkOutboundRouteMarker]; exists {
				return true
			}
		}
	}
	return false
}

func validateRootRemoteRuleSetNetworkOutboundRoutes(config map[string]any, snapshot Snapshot) error {
	route := nestedMap(config, "route")
	rules, ok := route["rules"].([]any)
	if !ok {
		return errors.New("root_remote_ruleset_network_outbound_invalid")
	}
	migratedTags := migratedRootRemoteRuleSetTags(config)
	for index, raw := range rules {
		rule, ok := raw.(map[string]any)
		if !ok {
			continue
		}
		marker, exists, err := rootRemoteRuleSetNetworkOutboundMarker(rule)
		if err != nil {
			return err
		}
		if !exists {
			continue
		}
		idx, idxOK := marker["index"].(float64)
		tags, tagsOK := marker["tags"].([]any)
		networks, networksOK := marker["networks"].([]any)
		outbound, outboundOK := marker["outbound"].(string)
		_, invertOK := marker["invert"].(bool)
		if !idxOK || math.IsNaN(idx) || math.IsInf(idx, 0) || math.Trunc(idx) != idx || idx != float64(index) ||
			!tagsOK || len(tags) == 0 || len(tags) > 256 || !networksOK || len(networks) == 0 || len(networks) > 2 ||
			!outboundOK || outbound == "" || len(outbound) > 256 || strings.ContainsAny(outbound, "\\/\x00\r\n") || !invertOK ||
			!validRootRouteNetworkOutboundTag(outbound) || !hasRootOutboundTag(config, snapshot, outbound) ||
			snapshot.RootInbound == nil || snapshot.RootInbound.Backend != "tun" {
			return errors.New("root_remote_ruleset_network_outbound_invalid")
		}
		seenTags := map[string]bool{}
		for _, value := range tags {
			tag, ok := value.(string)
			if !ok || !validRootRouteNetworkOutboundTag(tag) || seenTags[tag] || !migratedTags[tag] {
				return errors.New("root_remote_ruleset_network_outbound_invalid")
			}
			seenTags[tag] = true
		}
		seenNetworks := map[string]bool{}
		for _, value := range networks {
			network, ok := value.(string)
			if !ok || (network != "tcp" && network != "udp") || seenNetworks[network] {
				return errors.New("root_remote_ruleset_network_outbound_invalid")
			}
			seenNetworks[network] = true
		}
	}
	return nil
}

func materializeRootRemoteRuleSetNetworkOutboundRoutes(config map[string]any) error {
	route := nestedMap(config, "route")
	rules, ok := route["rules"].([]any)
	if !ok {
		return errors.New("root_remote_ruleset_network_outbound_invalid")
	}
	for index, raw := range rules {
		rule, ok := raw.(map[string]any)
		if !ok {
			continue
		}
		marker, exists, err := rootRemoteRuleSetNetworkOutboundMarker(rule)
		if err != nil {
			return err
		}
		if !exists {
			continue
		}
		idx, idxOK := marker["index"].(float64)
		tags, tagsOK := marker["tags"].([]any)
		networks, networksOK := marker["networks"].([]any)
		outbound, outboundOK := marker["outbound"].(string)
		invert, invertOK := marker["invert"].(bool)
		if !idxOK || idx != float64(index) || !tagsOK || !networksOK || !outboundOK || !invertOK {
			return errors.New("root_remote_ruleset_network_outbound_invalid")
		}
		generated := map[string]any{"rule_set": tags, "network": networks, "outbound": outbound}
		if invert {
			generated["invert"] = true
		}
		rules[index] = generated
	}
	route["rules"] = rules
	config["route"] = route
	return nil
}
