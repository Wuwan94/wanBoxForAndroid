// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package module

import (
	"errors"
	"math"
	"net/netip"
	"strings"
)

const rootCompositeDomainIPOutboundMarker = "_wanbox_root_composite_domain_ip_outbound_v51"

func compositeDomainIPOutboundMarkerValue(rule map[string]any) (map[string]any, bool, error) {
	value, exists := rule[rootCompositeDomainIPOutboundMarker]
	if !exists {
		return nil, false, nil
	}
	marker, ok := value.(map[string]any)
	if !ok || len(rule) != 1 || len(marker) != 6 {
		return nil, true, errors.New("root_composite_domain_ip_outbound_invalid")
	}
	for _, key := range []string{"index", "ruleId", "domainText", "ipText", "outbound", "invert"} {
		if _, ok := marker[key]; !ok {
			return nil, true, errors.New("root_composite_domain_ip_outbound_invalid")
		}
	}
	return marker, true, nil
}

func hasRootCompositeDomainIPOutboundMarker(config map[string]any) bool {
	route := nestedMap(config, "route")
	rules, _ := route["rules"].([]any)
	for _, raw := range rules {
		if rule, ok := raw.(map[string]any); ok {
			if _, exists := rule[rootCompositeDomainIPOutboundMarker]; exists {
				return true
			}
		}
	}
	return false
}

func parseRootCompositeIPCIDRs(text string) ([]string, error) {
	if len(text) == 0 || len(text) > 4096 {
		return nil, errors.New("root_composite_domain_ip_outbound_invalid")
	}
	parts := strings.FieldsFunc(text, func(r rune) bool { return r == ',' || r == '\n' })
	cidrs := make([]string, 0, len(parts))
	for _, raw := range parts {
		value := strings.TrimSpace(raw)
		prefix, err := netip.ParsePrefix(value)
		if err != nil || !prefix.IsValid() || prefix.Bits() < 0 ||
			(!prefix.Addr().Is4() && !prefix.Addr().Is6()) ||
			(prefix.Addr().Is4() && prefix.Bits() > 32) || (prefix.Addr().Is6() && prefix.Bits() > 128) {
			return nil, errors.New("root_composite_domain_ip_outbound_invalid")
		}
		cidrs = append(cidrs, value)
	}
	if len(cidrs) == 0 || len(cidrs) > 256 {
		return nil, errors.New("root_composite_domain_ip_outbound_invalid")
	}
	return cidrs, nil
}

func validateRootCompositeDomainIPOutbound(config map[string]any) error {
	route := nestedMap(config, "route")
	rules, _ := route["rules"].([]any)
	seen := map[int64]bool{}
	for index, raw := range rules {
		rule, ok := raw.(map[string]any)
		if !ok {
			continue
		}
		marker, exists, err := compositeDomainIPOutboundMarkerValue(rule)
		if err != nil {
			return err
		}
		if !exists {
			continue
		}
		idx, idxOK := marker["index"].(float64)
		id, idOK := marker["ruleId"].(float64)
		domainText, domainOK := marker["domainText"].(string)
		ipText, ipOK := marker["ipText"].(string)
		outbound, outboundOK := marker["outbound"].(string)
		_, invertOK := marker["invert"].(bool)
		if !idxOK || math.IsNaN(idx) || math.IsInf(idx, 0) || math.Trunc(idx) != idx || idx != float64(index) ||
			!idOK || math.IsNaN(id) || math.IsInf(id, 0) || math.Trunc(id) != id || id < 1 || id > float64(maxRootRouteDomainRejectRuleID) || seen[int64(id)] ||
			!domainOK || !ipOK || !outboundOK || outbound == "" || len(outbound) > 256 || strings.ContainsAny(outbound, "\\/\x00\r\n") || !invertOK {
			return errors.New("root_composite_domain_ip_outbound_invalid")
		}
		if _, _, _, err := parseRootDNSDomainMatchers(domainText); err != nil {
			return errors.New("root_composite_domain_ip_outbound_invalid")
		}
		if _, err := parseRootCompositeIPCIDRs(ipText); err != nil {
			return err
		}
		seen[int64(id)] = true
	}
	return nil
}

func materializeRootCompositeDomainIPOutbound(config map[string]any) error {
	if err := validateRootCompositeDomainIPOutbound(config); err != nil {
		return err
	}
	route := nestedMap(config, "route")
	rules, _ := route["rules"].([]any)
	for index, raw := range rules {
		rule, ok := raw.(map[string]any)
		if !ok {
			continue
		}
		marker, exists, err := compositeDomainIPOutboundMarkerValue(rule)
		if err != nil {
			return err
		}
		if !exists {
			continue
		}
		domains, suffixes, keywords, err := parseRootDNSDomainMatchers(marker["domainText"].(string))
		if err != nil {
			return errors.New("root_composite_domain_ip_outbound_invalid")
		}
		domainMatcher := map[string]any{}
		if len(domains) > 0 {
			domainMatcher["domain"] = domains
		}
		if len(suffixes) > 0 {
			domainMatcher["domain_suffix"] = suffixes
		}
		if len(keywords) > 0 {
			domainMatcher["domain_keyword"] = keywords
		}
		cidrs, err := parseRootCompositeIPCIDRs(marker["ipText"].(string))
		if err != nil {
			return err
		}
		children := []any{
			domainMatcher,
			map[string]any{"ip_cidr": cidrs},
		}
		group := map[string]any{"type": "logical", "mode": "or", "rules": children, "outbound": marker["outbound"].(string)}
		if marker["invert"].(bool) {
			group["invert"] = true
		}
		rules[index] = group
	}
	route["rules"] = rules
	config["route"] = route
	return nil
}
