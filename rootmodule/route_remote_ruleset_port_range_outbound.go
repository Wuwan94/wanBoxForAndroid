// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package module

import (
	"errors"
	"math"
	"strconv"
	"strings"
)

const rootRemoteRuleSetPortRangeOutboundRouteMarker = "_wanbox_root_remote_ruleset_port_range_outbound_v76"

func remoteRuleSetPortRangeOutboundMarker(rule map[string]any) (map[string]any, bool, error) {
	value, exists := rule[rootRemoteRuleSetPortRangeOutboundRouteMarker]
	if !exists {
		return nil, false, nil
	}
	marker, ok := value.(map[string]any)
	if !ok || len(rule) != 1 || len(marker) != 8 {
		return nil, true, errors.New("root_remote_ruleset_port_range_outbound_invalid")
	}
	for _, key := range []string{"index", "tags", "ports", "portRanges", "sourcePorts", "sourcePortRanges", "outbound", "invert"} {
		if _, ok := marker[key]; !ok {
			return nil, true, errors.New("root_remote_ruleset_port_range_outbound_invalid")
		}
	}
	return marker, true, nil
}

func hasRootRemoteRuleSetPortRangeOutboundRouteMarker(config map[string]any) bool {
	rules, _ := nestedMap(config, "route")["rules"].([]any)
	for _, raw := range rules {
		if rule, ok := raw.(map[string]any); ok {
			if _, exists := rule[rootRemoteRuleSetPortRangeOutboundRouteMarker]; exists {
				return true
			}
		}
	}
	return false
}

func validRootPortRanges(values []any, allowEmpty bool) bool {
	if len(values) > 256 || (!allowEmpty && len(values) == 0) {
		return false
	}
	for _, raw := range values {
		text, ok := raw.(string)
		if !ok || strings.TrimSpace(text) != text || text == "" {
			return false
		}
		bounds := strings.Split(text, ":")
		if len(bounds) != 2 || bounds[0] == "" || bounds[1] == "" {
			return false
		}
		first, err1 := strconv.Atoi(bounds[0])
		last, err2 := strconv.Atoi(bounds[1])
		if err1 != nil || err2 != nil || first < 1 || last < first || last > 65535 {
			return false
		}
	}
	return true
}

func validRootPortNumbers(values []any) bool {
	if len(values) > 256 {
		return false
	}
	for _, raw := range values {
		value, ok := raw.(float64)
		if !ok || math.IsNaN(value) || math.IsInf(value, 0) || math.Trunc(value) != value || value < 0 || value > 65535 {
			return false
		}
	}
	return true
}

func validateRootRemoteRuleSetPortRangeOutboundRoutes(config map[string]any, snapshot Snapshot) error {
	rules, ok := nestedMap(config, "route")["rules"].([]any)
	if !ok {
		return errors.New("root_remote_ruleset_port_range_outbound_invalid")
	}
	migratedTags := migratedRootRemoteRuleSetTags(config)
	for index, raw := range rules {
		rule, ok := raw.(map[string]any)
		if !ok {
			continue
		}
		marker, exists, err := remoteRuleSetPortRangeOutboundMarker(rule)
		if err != nil {
			return err
		}
		if !exists {
			continue
		}
		idx, idxOK := marker["index"].(float64)
		tags, tagsOK := marker["tags"].([]any)
		ports, portsOK := marker["ports"].([]any)
		portRanges, portRangesOK := marker["portRanges"].([]any)
		sourcePorts, sourcePortsOK := marker["sourcePorts"].([]any)
		sourcePortRanges, sourcePortRangesOK := marker["sourcePortRanges"].([]any)
		outbound, outboundOK := marker["outbound"].(string)
		_, invertOK := marker["invert"].(bool)
		if !idxOK || math.IsNaN(idx) || math.IsInf(idx, 0) || math.Trunc(idx) != idx || idx != float64(index) ||
			!tagsOK || len(tags) == 0 || len(tags) > 256 || !portsOK || !portRangesOK || !sourcePortsOK || !sourcePortRangesOK ||
			!validRootPortNumbers(ports) || !validRootPortRanges(portRanges, true) || !validRootPortNumbers(sourcePorts) || !validRootPortRanges(sourcePortRanges, true) ||
			(len(portRanges) == 0 && len(sourcePortRanges) == 0) ||
			!outboundOK || outbound == "" || len(outbound) > 256 || strings.ContainsAny(outbound, "\\/\x00\r\n") || !invertOK ||
			!validRootRouteNetworkOutboundTag(outbound) || !hasRootOutboundTag(config, snapshot, outbound) || snapshot.RootInbound == nil || snapshot.RootInbound.Backend != "tun" {
			return errors.New("root_remote_ruleset_port_range_outbound_invalid")
		}
		seenTags := map[string]bool{}
		for _, value := range tags {
			tag, ok := value.(string)
			if !ok || !validRootRouteNetworkOutboundTag(tag) || seenTags[tag] || !migratedTags[tag] {
				return errors.New("root_remote_ruleset_port_range_outbound_invalid")
			}
			seenTags[tag] = true
		}
	}
	return nil
}

func materializeRootRemoteRuleSetPortRangeOutboundRoutes(config map[string]any) error {
	route := nestedMap(config, "route")
	rules, ok := route["rules"].([]any)
	if !ok {
		return errors.New("root_remote_ruleset_port_range_outbound_invalid")
	}
	for index, raw := range rules {
		rule, ok := raw.(map[string]any)
		if !ok {
			continue
		}
		marker, exists, err := remoteRuleSetPortRangeOutboundMarker(rule)
		if err != nil {
			return err
		}
		if !exists {
			continue
		}
		idx, idxOK := marker["index"].(float64)
		tags, tagsOK := marker["tags"].([]any)
		ports, portsOK := marker["ports"].([]any)
		portRanges, portRangesOK := marker["portRanges"].([]any)
		sourcePorts, sourcePortsOK := marker["sourcePorts"].([]any)
		sourcePortRanges, sourcePortRangesOK := marker["sourcePortRanges"].([]any)
		outbound, outboundOK := marker["outbound"].(string)
		invert, invertOK := marker["invert"].(bool)
		if !idxOK || math.IsNaN(idx) || math.IsInf(idx, 0) || math.Trunc(idx) != idx || !tagsOK || !portsOK || !portRangesOK || !sourcePortsOK || !sourcePortRangesOK || !outboundOK || !invertOK {
			return errors.New("root_remote_ruleset_port_range_outbound_invalid")
		}
		generated := map[string]any{"rule_set": tags, "outbound": outbound}
		if len(ports) > 0 {
			generated["port"] = ports
		}
		if len(portRanges) > 0 {
			generated["port_range"] = portRanges
		}
		if len(sourcePorts) > 0 {
			generated["source_port"] = sourcePorts
		}
		if len(sourcePortRanges) > 0 {
			generated["source_port_range"] = sourcePortRanges
		}
		if invert {
			generated["invert"] = true
		}
		rules[index] = generated
	}
	route["rules"] = rules
	config["route"] = route
	return nil
}
