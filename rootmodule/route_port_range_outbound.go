// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package module

import (
	"errors"
	"math"
	"strconv"
	"strings"
)

const rootPortRangeOutboundMarker = "_wanbox_root_route_port_range_outbound_v85"
const rootSourcePortRangeOutboundMarker = "_wanbox_root_route_source_port_range_outbound_v85"
const maxRootPortRangeOutboundRuleID int64 = 9_007_199_254_740_991

func portRangeOutboundMarkerValue(rule map[string]any, key string) (map[string]any, bool, error) {
	value, exists := rule[key]
	if !exists {
		return nil, false, nil
	}
	marker, ok := value.(map[string]any)
	if !ok || len(rule) != 1 || len(marker) != 4 {
		return nil, true, errors.New("root_port_range_outbound_invalid")
	}
	for _, field := range []string{"index", "ruleId", "ranges", "outbound"} {
		if _, ok := marker[field]; !ok {
			return nil, true, errors.New("root_port_range_outbound_invalid")
		}
	}
	return marker, true, nil
}

func hasRootPortRangeOutboundMarker(config map[string]any, key string) bool {
	rules, _ := nestedMap(config, "route")["rules"].([]any)
	for _, raw := range rules {
		if rule, ok := raw.(map[string]any); ok {
			if _, exists := rule[key]; exists {
				return true
			}
		}
	}
	return false
}

func validRootClosedPortRanges(values []any) bool {
	if len(values) == 0 || len(values) > 256 {
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
		for _, bound := range bounds {
			for _, digit := range bound {
				if digit < '0' || digit > '9' {
					return false
				}
			}
		}
		first, err1 := strconv.Atoi(bounds[0])
		last, err2 := strconv.Atoi(bounds[1])
		if err1 != nil || err2 != nil || first < 1 || last < first || last > 65535 {
			return false
		}
	}
	return true
}

func validateRootPortRangeOutbound(config map[string]any, snapshot Snapshot, key string) error {
	rules, ok := nestedMap(config, "route")["rules"].([]any)
	if !ok {
		return errors.New("root_port_range_outbound_invalid")
	}
	seen := map[int64]bool{}
	for index, raw := range rules {
		rule, ok := raw.(map[string]any)
		if !ok {
			continue
		}
		marker, exists, err := portRangeOutboundMarkerValue(rule, key)
		if err != nil {
			return err
		}
		if !exists {
			continue
		}
		idx, idxOK := marker["index"].(float64)
		id, idOK := marker["ruleId"].(float64)
		ranges, rangesOK := marker["ranges"].([]any)
		outbound, outboundOK := marker["outbound"].(string)
		if !idxOK || math.IsNaN(idx) || math.IsInf(idx, 0) || math.Trunc(idx) != idx || idx != float64(index) ||
			!idOK || math.IsNaN(id) || math.IsInf(id, 0) || math.Trunc(id) != id || id < 1 || id > float64(maxRootPortRangeOutboundRuleID) || seen[int64(id)] ||
			!rangesOK || !validRootClosedPortRanges(ranges) || !outboundOK || outbound == "" || len(outbound) > 256 || strings.ContainsAny(outbound, "\\/\x00\r\n") ||
			!validRootRouteNetworkOutboundTag(outbound) || !hasRootOutboundTag(config, snapshot, outbound) || snapshot.RootInbound == nil || snapshot.RootInbound.Backend != "tun" {
			return errors.New("root_port_range_outbound_invalid")
		}
		seen[int64(id)] = true
	}
	return nil
}

func materializeRootPortRangeOutbound(config map[string]any, key, field string) error {
	route := nestedMap(config, "route")
	rules, ok := route["rules"].([]any)
	if !ok {
		return errors.New("root_port_range_outbound_invalid")
	}
	for index, raw := range rules {
		rule, ok := raw.(map[string]any)
		if !ok {
			continue
		}
		marker, exists, err := portRangeOutboundMarkerValue(rule, key)
		if err != nil {
			return err
		}
		if !exists {
			continue
		}
		ranges, rangesOK := marker["ranges"].([]any)
		outbound, outboundOK := marker["outbound"].(string)
		if !rangesOK || !validRootClosedPortRanges(ranges) || !outboundOK || outbound == "" {
			return errors.New("root_port_range_outbound_invalid")
		}
		rules[index] = map[string]any{field: ranges, "outbound": outbound}
	}
	route["rules"] = rules
	config["route"] = route
	return nil
}
