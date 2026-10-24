// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package module

import (
	"errors"
	"math"
	"strings"
)

const rootRemoteRuleSetProtocolOutboundRouteMarker = "_wanbox_root_remote_ruleset_protocol_outbound_v77"

func validRootRouteProtocolOutboundProtocol(protocol string) bool {
	_, ok := rootRouteRejectProtocols[protocol]
	return ok
}

func rootRemoteRuleSetProtocolOutboundMarker(rule map[string]any) (map[string]any, bool, error) {
	value, exists := rule[rootRemoteRuleSetProtocolOutboundRouteMarker]
	if !exists {
		return nil, false, nil
	}
	marker, ok := value.(map[string]any)
	if !ok || len(rule) != 1 || len(marker) != 5 {
		return nil, true, errors.New("root_remote_ruleset_protocol_outbound_invalid")
	}
	for _, key := range []string{"index", "tags", "protocols", "outbound", "invert"} {
		if _, ok := marker[key]; !ok {
			return nil, true, errors.New("root_remote_ruleset_protocol_outbound_invalid")
		}
	}
	return marker, true, nil
}

func hasRootRemoteRuleSetProtocolOutboundRouteMarker(config map[string]any) bool {
	rules, _ := nestedMap(config, "route")["rules"].([]any)
	for _, raw := range rules {
		if rule, ok := raw.(map[string]any); ok {
			if _, exists := rule[rootRemoteRuleSetProtocolOutboundRouteMarker]; exists {
				return true
			}
		}
	}
	return false
}

func validateRootRemoteRuleSetProtocolOutboundRoutes(config map[string]any, snapshot Snapshot) error {
	route := nestedMap(config, "route")
	rules, ok := route["rules"].([]any)
	if !ok {
		return errors.New("root_remote_ruleset_protocol_outbound_invalid")
	}
	migratedTags := migratedRootRemoteRuleSetTags(config)
	for index, raw := range rules {
		rule, ok := raw.(map[string]any)
		if !ok {
			continue
		}
		marker, exists, err := rootRemoteRuleSetProtocolOutboundMarker(rule)
		if err != nil {
			return err
		}
		if !exists {
			continue
		}
		idx, idxOK := marker["index"].(float64)
		tags, tagsOK := marker["tags"].([]any)
		protocols, protocolsOK := marker["protocols"].([]any)
		outbound, outboundOK := marker["outbound"].(string)
		_, invertOK := marker["invert"].(bool)
		if !idxOK || math.IsNaN(idx) || math.IsInf(idx, 0) || math.Trunc(idx) != idx || idx != float64(index) ||
			!tagsOK || len(tags) == 0 || len(tags) > 256 || !protocolsOK || len(protocols) == 0 || len(protocols) > 10 ||
			!outboundOK || outbound == "" || len(outbound) > 256 || strings.ContainsAny(outbound, "\\/\x00\r\n") || !invertOK ||
			!validRootRouteNetworkOutboundTag(outbound) || !hasRootOutboundTag(config, snapshot, outbound) ||
			snapshot.RootInbound == nil || snapshot.RootInbound.Backend != "tun" {
			return errors.New("root_remote_ruleset_protocol_outbound_invalid")
		}
		seenTags := map[string]bool{}
		for _, value := range tags {
			tag, ok := value.(string)
			if !ok || !validRootRouteNetworkOutboundTag(tag) || seenTags[tag] || !migratedTags[tag] {
				return errors.New("root_remote_ruleset_protocol_outbound_invalid")
			}
			seenTags[tag] = true
		}
		seenProtocols := map[string]bool{}
		for _, value := range protocols {
			protocol, ok := value.(string)
			if !ok || !validRootRouteProtocolOutboundProtocol(protocol) || seenProtocols[protocol] {
				return errors.New("root_remote_ruleset_protocol_outbound_invalid")
			}
			seenProtocols[protocol] = true
		}
	}
	return nil
}

func materializeRootRemoteRuleSetProtocolOutboundRoutes(config map[string]any) error {
	route := nestedMap(config, "route")
	rules, ok := route["rules"].([]any)
	if !ok {
		return errors.New("root_remote_ruleset_protocol_outbound_invalid")
	}
	for index, raw := range rules {
		rule, ok := raw.(map[string]any)
		if !ok {
			continue
		}
		marker, exists, err := rootRemoteRuleSetProtocolOutboundMarker(rule)
		if err != nil {
			return err
		}
		if !exists {
			continue
		}
		idx, idxOK := marker["index"].(float64)
		tags, tagsOK := marker["tags"].([]any)
		protocols, protocolsOK := marker["protocols"].([]any)
		outbound, outboundOK := marker["outbound"].(string)
		invert, invertOK := marker["invert"].(bool)
		// Earlier Root projections may insert rules before this marker. CheckSnapshot
		// validates its original slot; at materialization time the marker's current
		// array position is authoritative so the rule stays in its relative order.
		if !idxOK || math.IsNaN(idx) || math.IsInf(idx, 0) || math.Trunc(idx) != idx || idx < 0 || !tagsOK || !protocolsOK || !outboundOK || !invertOK {
			return errors.New("root_remote_ruleset_protocol_outbound_invalid")
		}
		generated := map[string]any{"rule_set": tags, "protocol": protocols, "outbound": outbound}
		if invert {
			generated["invert"] = true
		}
		rules[index] = generated
	}
	route["rules"] = rules
	config["route"] = route
	return nil
}
