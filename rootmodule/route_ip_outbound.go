// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package module

import (
	"errors"
	"math"
	"net/netip"
	"strings"
)

const rootIPOutboundRouteMarker = "_wanbox_root_ip_outbound_v52"

func hasRootIPOutboundRouteMarker(config map[string]any) bool {
	route := nestedMap(config, "route")
	rules, _ := route["rules"].([]any)
	for _, raw := range rules {
		if rule, ok := raw.(map[string]any); ok {
			if _, exists := rule[rootIPOutboundRouteMarker]; exists {
				return true
			}
		}
	}
	return false
}

func parseRootIPOutboundCIDRs(text string) ([]string, error) {
	if len(text) == 0 || len(text) > 4096 {
		return nil, errors.New("root_ip_outbound_invalid")
	}
	parts := strings.FieldsFunc(text, func(r rune) bool { return r == ',' || r == '\n' })
	out := make([]string, 0, len(parts))
	for _, raw := range parts {
		value := strings.TrimSpace(raw)
		prefix, err := netip.ParsePrefix(value)
		if err != nil || !prefix.IsValid() || prefix.Bits() < 0 ||
			(prefix.Addr().Is4() && prefix.Bits() > 32) || (prefix.Addr().Is6() && prefix.Bits() > 128) {
			return nil, errors.New("root_ip_outbound_invalid")
		}
		out = append(out, value)
	}
	if len(out) == 0 || len(out) > 256 {
		return nil, errors.New("root_ip_outbound_invalid")
	}
	return out, nil
}

func validateRootIPOutboundRoute(config map[string]any, snapshot Snapshot) error {
	route := nestedMap(config, "route")
	rules, _ := route["rules"].([]any)
	seen := map[int64]bool{}
	for index, raw := range rules {
		rule, ok := raw.(map[string]any)
		if !ok {
			continue
		}
		markerRaw, exists := rule[rootIPOutboundRouteMarker]
		if !exists {
			continue
		}
		marker, ok := markerRaw.(map[string]any)
		if !ok || len(rule) != 1 || len(marker) != 5 {
			return errors.New("root_ip_outbound_invalid")
		}
		idx, idxOK := marker["index"].(float64)
		id, idOK := marker["ruleId"].(float64)
		ipText, ipOK := marker["ipText"].(string)
		outbound, outboundOK := marker["outbound"].(string)
		_, invertOK := marker["invert"].(bool)
		if !idxOK || math.IsNaN(idx) || math.IsInf(idx, 0) || math.Trunc(idx) != idx || idx != float64(index) ||
			!idOK || math.IsNaN(id) || math.IsInf(id, 0) || math.Trunc(id) != id || id < 1 || id > float64(maxRootRouteDomainRejectRuleID) || seen[int64(id)] ||
			!ipOK || !outboundOK || outbound == "" || len(outbound) > 256 || strings.ContainsAny(outbound, "\\/\x00\r\n") || !invertOK || !hasRootOutboundTag(config, snapshot, outbound) {
			return errors.New("root_ip_outbound_invalid")
		}
		if _, err := parseRootIPOutboundCIDRs(ipText); err != nil {
			return err
		}
		seen[int64(id)] = true
	}
	return nil
}

func materializeRootIPOutboundRoute(config map[string]any) error {
	route := nestedMap(config, "route")
	rules, _ := route["rules"].([]any)
	for index, raw := range rules {
		rule, ok := raw.(map[string]any)
		if !ok {
			continue
		}
		markerRaw, exists := rule[rootIPOutboundRouteMarker]
		if !exists {
			continue
		}
		marker := markerRaw.(map[string]any)
		cidrs, err := parseRootIPOutboundCIDRs(marker["ipText"].(string))
		if err != nil {
			return err
		}
		generated := map[string]any{"ip_cidr": cidrs, "outbound": marker["outbound"].(string)}
		if marker["invert"].(bool) {
			generated["invert"] = true
		}
		rules[index] = generated
	}
	route["rules"] = rules
	config["route"] = route
	return nil
}
