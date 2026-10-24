// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package module

import (
	"errors"
	"math"
	"strings"
)

const rootExactDomainOutboundMarker = "_wanbox_root_exact_domain_outbound_v50"

func exactDomainOutboundMarkerValue(rule map[string]any) (map[string]any, bool, error) {
	value, exists := rule[rootExactDomainOutboundMarker]
	if !exists {
		return nil, false, nil
	}
	marker, ok := value.(map[string]any)
	if !ok || len(rule) != 1 || len(marker) != 4 {
		return nil, true, errors.New("root_exact_domain_outbound_invalid")
	}
	for _, key := range []string{"index", "ruleId", "domainText", "outbound"} {
		if _, ok := marker[key]; !ok {
			return nil, true, errors.New("root_exact_domain_outbound_invalid")
		}
	}
	return marker, true, nil
}

func hasRootExactDomainOutboundMarker(config map[string]any) bool {
	route, _ := config["route"].(map[string]any)
	rules, _ := route["rules"].([]any)
	for _, raw := range rules {
		if rule, ok := raw.(map[string]any); ok {
			if _, exists := rule[rootExactDomainOutboundMarker]; exists {
				return true
			}
		}
	}
	return false
}

func validateRootExactDomainOutbound(config map[string]any) error {
	route, _ := config["route"].(map[string]any)
	rules, _ := route["rules"].([]any)
	seen := map[int64]bool{}
	for index, raw := range rules {
		rule, ok := raw.(map[string]any)
		if !ok {
			continue
		}
		marker, exists, err := exactDomainOutboundMarkerValue(rule)
		if err != nil {
			return err
		}
		if !exists {
			continue
		}
		idx, ok := marker["index"].(float64)
		id, idOK := marker["ruleId"].(float64)
		domainText, domainOK := marker["domainText"].(string)
		outbound, outboundOK := marker["outbound"].(string)
		if !ok || math.IsNaN(idx) || math.IsInf(idx, 0) || math.Trunc(idx) != idx || idx != float64(index) ||
			!idOK || math.IsNaN(id) || math.IsInf(id, 0) || math.Trunc(id) != id || id < 1 || id > float64(maxRootRouteDomainRejectRuleID) || seen[int64(id)] ||
			!domainOK || !outboundOK || outbound == "" || len(outbound) > 256 || strings.ContainsAny(outbound, "\\/\x00\r\n") {
			return errors.New("root_exact_domain_outbound_invalid")
		}
		if _, err := parseRootRouteExactDomains(domainText); err != nil {
			return errors.New("root_exact_domain_outbound_invalid")
		}
		seen[int64(id)] = true
	}
	return nil
}

func materializeRootExactDomainOutbound(config map[string]any) error {
	if err := validateRootExactDomainOutbound(config); err != nil {
		return err
	}
	route, _ := config["route"].(map[string]any)
	rules, _ := route["rules"].([]any)
	for index, raw := range rules {
		rule, ok := raw.(map[string]any)
		if !ok {
			continue
		}
		marker, exists, err := exactDomainOutboundMarkerValue(rule)
		if err != nil {
			return err
		}
		if !exists {
			continue
		}
		domains, err := parseRootRouteExactDomains(marker["domainText"].(string))
		if err != nil {
			return errors.New("root_exact_domain_outbound_invalid")
		}
		rules[index] = map[string]any{"domain": domains, "outbound": marker["outbound"].(string)}
	}
	route["rules"] = rules
	config["route"] = route
	return nil
}
