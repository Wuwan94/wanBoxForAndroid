// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package module

import (
	"errors"
	"math"
	"strings"
)

const rootSourceDestinationIPOutboundMarker = "_wanbox_root_route_source_destination_ip_outbound_v60"
const maxRootSourceDestinationIPOutboundRuleID int64 = 9_007_199_254_740_991

func sourceDestinationIPOutboundMarkerValue(rule map[string]any) (map[string]any, bool, error) {
	value, exists := rule[rootSourceDestinationIPOutboundMarker]
	if !exists {
		return nil, false, nil
	}
	marker, ok := value.(map[string]any)
	if !ok || len(rule) != 1 || len(marker) != 5 {
		return nil, true, errors.New("root_source_destination_ip_outbound_invalid")
	}
	for _, key := range []string{"index", "ruleId", "sourceText", "ipText", "outbound"} {
		if _, ok := marker[key]; !ok {
			return nil, true, errors.New("root_source_destination_ip_outbound_invalid")
		}
	}
	return marker, true, nil
}

func hasRootSourceDestinationIPOutboundMarker(config map[string]any) bool {
	route := nestedMap(config, "route")
	rules, _ := route["rules"].([]any)
	for _, raw := range rules {
		if rule, ok := raw.(map[string]any); ok {
			if _, exists := rule[rootSourceDestinationIPOutboundMarker]; exists {
				return true
			}
		}
	}
	return false
}

func validateRootSourceDestinationIPOutbound(config map[string]any) error {
	route := nestedMap(config, "route")
	rules, _ := route["rules"].([]any)
	seen := map[int64]bool{}
	for index, raw := range rules {
		rule, ok := raw.(map[string]any)
		if !ok {
			continue
		}
		marker, exists, err := sourceDestinationIPOutboundMarkerValue(rule)
		if err != nil {
			return err
		}
		if !exists {
			continue
		}
		idx, idxOK := marker["index"].(float64)
		id, idOK := marker["ruleId"].(float64)
		sourceText, sourceOK := marker["sourceText"].(string)
		ipText, ipOK := marker["ipText"].(string)
		outbound, outboundOK := marker["outbound"].(string)
		if !idxOK || math.IsNaN(idx) || math.IsInf(idx, 0) || math.Trunc(idx) != idx || idx != float64(index) ||
			!idOK || math.IsNaN(id) || math.IsInf(id, 0) || math.Trunc(id) != id || id < 1 || id > float64(maxRootSourceDestinationIPOutboundRuleID) || seen[int64(id)] ||
			!sourceOK || !ipOK || !outboundOK || outbound == "" || outbound == "block" || len(outbound) > 256 || strings.ContainsAny(outbound, "\\/\x00\r\n") {
			return errors.New("root_source_destination_ip_outbound_invalid")
		}
		if _, err := parseRootRouteSourceIPRejectCIDRs(sourceText); err != nil {
			return errors.New("root_source_destination_ip_outbound_invalid")
		}
		if _, err := parseRootRouteIPRejectCIDRs(ipText); err != nil {
			return errors.New("root_source_destination_ip_outbound_invalid")
		}
		seen[int64(id)] = true
	}
	return nil
}

func materializeRootSourceDestinationIPOutbound(config map[string]any) error {
	if err := validateRootSourceDestinationIPOutbound(config); err != nil {
		return err
	}
	route := nestedMap(config, "route")
	rules, _ := route["rules"].([]any)
	for index, raw := range rules {
		rule, ok := raw.(map[string]any)
		if !ok {
			continue
		}
		marker, exists, err := sourceDestinationIPOutboundMarkerValue(rule)
		if err != nil {
			return err
		}
		if !exists {
			continue
		}
		sourceText := marker["sourceText"].(string)
		ipText := marker["ipText"].(string)
		source, err := parseRootRouteSourceIPRejectCIDRs(sourceText)
		if err != nil {
			return err
		}
		ips, err := parseRootRouteIPRejectCIDRs(ipText)
		if err != nil {
			return err
		}
		rules[index] = map[string]any{"source_ip_cidr": source, "ip_cidr": ips, "outbound": marker["outbound"].(string)}
	}
	route["rules"] = rules
	config["route"] = route
	return nil
}
