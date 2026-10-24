// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package module

import (
	"errors"
	"math"
	"strings"
)

const rootIPVersionOutboundMarker = "_wanbox_root_ip_version_outbound_v62"
const maxRootIPVersionOutboundRuleID int64 = 9_007_199_254_740_991

func ipVersionOutboundMarkerValue(rule map[string]any) (map[string]any, bool, error) {
	value, exists := rule[rootIPVersionOutboundMarker]
	if !exists {
		return nil, false, nil
	}
	marker, ok := value.(map[string]any)
	if !ok || len(rule) != 1 || len(marker) != 4 {
		return nil, true, errors.New("root_ip_version_outbound_invalid")
	}
	for _, key := range []string{"index", "ruleId", "ipVersion", "outbound"} {
		if _, ok := marker[key]; !ok {
			return nil, true, errors.New("root_ip_version_outbound_invalid")
		}
	}
	return marker, true, nil
}

func hasRootIPVersionOutboundMarker(config map[string]any) bool {
	rules, _ := nestedMap(config, "route")["rules"].([]any)
	for _, raw := range rules {
		if rule, ok := raw.(map[string]any); ok {
			if _, exists := rule[rootIPVersionOutboundMarker]; exists {
				return true
			}
		}
	}
	return false
}

func validateRootIPVersionOutbound(config map[string]any, snapshot Snapshot) error {
	rules, _ := nestedMap(config, "route")["rules"].([]any)
	seen := map[int64]bool{}
	for index, raw := range rules {
		rule, ok := raw.(map[string]any)
		if !ok {
			continue
		}
		marker, exists, err := ipVersionOutboundMarkerValue(rule)
		if err != nil {
			return err
		}
		if !exists {
			continue
		}
		idx, idxOK := marker["index"].(float64)
		id, idOK := marker["ruleId"].(float64)
		version, versionOK := marker["ipVersion"].(float64)
		outbound, outboundOK := marker["outbound"].(string)
		if !idxOK || math.IsNaN(idx) || math.IsInf(idx, 0) || math.Trunc(idx) != idx || idx != float64(index) ||
			!idOK || math.IsNaN(id) || math.IsInf(id, 0) || math.Trunc(id) != id || id < 1 || id > float64(maxRootIPVersionOutboundRuleID) || seen[int64(id)] ||
			!versionOK || (version != 4 && version != 6) || !outboundOK || outbound == "" || outbound == "block" || len(outbound) > 256 || strings.ContainsAny(outbound, "\\/\x00\r\n") ||
			!validRootRouteNetworkOutboundTag(outbound) || !hasRootOutboundTag(config, snapshot, outbound) {
			return errors.New("root_ip_version_outbound_invalid")
		}
		seen[int64(id)] = true
	}
	return nil
}

func materializeRootIPVersionOutbound(config map[string]any) error {
	route := nestedMap(config, "route")
	rules, _ := route["rules"].([]any)
	for index, raw := range rules {
		rule, ok := raw.(map[string]any)
		if !ok {
			continue
		}
		marker, exists, err := ipVersionOutboundMarkerValue(rule)
		if err != nil {
			return err
		}
		if !exists {
			continue
		}
		idx, idxOK := marker["index"].(float64)
		version, versionOK := marker["ipVersion"].(float64)
		outbound, outboundOK := marker["outbound"].(string)
		if !idxOK || idx != float64(index) || !versionOK || (version != 4 && version != 6) || !outboundOK || outbound == "" {
			return errors.New("root_ip_version_outbound_invalid")
		}
		rules[index] = map[string]any{"ip_version": version, "outbound": outbound}
	}
	route["rules"] = rules
	config["route"] = route
	return nil
}
