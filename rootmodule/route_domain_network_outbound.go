// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package module

import (
	"errors"
	"math"
	"strings"
)

const rootDomainNetworkOutboundRouteMarker = "_wanbox_root_domain_network_outbound_v58"

func hasRootDomainNetworkOutboundRouteMarker(config map[string]any) bool {
	rules, _ := nestedMap(config, "route")["rules"].([]any)
	for _, raw := range rules {
		if rule, ok := raw.(map[string]any); ok {
			if _, exists := rule[rootDomainNetworkOutboundRouteMarker]; exists {
				return true
			}
		}
	}
	return false
}

func validateRootDomainNetworkOutboundRoutes(config map[string]any, snapshot Snapshot) error {
	rules, ok := nestedMap(config, "route")["rules"].([]any)
	if !ok {
		return errors.New("root_domain_network_outbound_invalid")
	}
	seen := map[int64]bool{}
	for index, raw := range rules {
		rule, ok := raw.(map[string]any)
		if !ok {
			continue
		}
		value, exists := rule[rootDomainNetworkOutboundRouteMarker]
		if !exists {
			continue
		}
		marker, ok := value.(map[string]any)
		if !ok || len(rule) != 1 || len(marker) != 6 {
			return errors.New("root_domain_network_outbound_invalid")
		}
		idx, idxOK := marker["index"].(float64)
		id, idOK := marker["ruleId"].(float64)
		domainText, domainOK := marker["domainText"].(string)
		networkText, networkOK := marker["networkText"].(string)
		outbound, outboundOK := marker["outbound"].(string)
		_, invertOK := marker["invert"].(bool)
		if !idxOK || math.IsNaN(idx) || math.IsInf(idx, 0) || math.Trunc(idx) != idx || idx != float64(index) ||
			!idOK || math.IsNaN(id) || math.IsInf(id, 0) || math.Trunc(id) != id || id < 1 || id > float64(maxRootRouteDomainRejectRuleID) || seen[int64(id)] ||
			!domainOK || !networkOK || !outboundOK || !invertOK || strings.TrimSpace(outbound) != outbound || outbound == "" ||
			!validRootRouteNetworkOutboundTag(outbound) || !hasRootOutboundTag(config, snapshot, outbound) {
			return errors.New("root_domain_network_outbound_invalid")
		}
		if _, _, _, err := parseRootDNSDomainMatchers(domainText); err != nil {
			return errors.New("root_domain_network_outbound_invalid")
		}
		if _, err := parseRootRouteNetworkRejectNetworks(networkText); err != nil {
			return errors.New("root_domain_network_outbound_invalid")
		}
		seen[int64(id)] = true
	}
	return nil
}

func materializeRootDomainNetworkOutboundRoutes(config map[string]any) error {
	route := nestedMap(config, "route")
	rules, ok := route["rules"].([]any)
	if !ok {
		return errors.New("root_domain_network_outbound_invalid")
	}
	for index, raw := range rules {
		rule, ok := raw.(map[string]any)
		if !ok {
			continue
		}
		value, exists := rule[rootDomainNetworkOutboundRouteMarker]
		if !exists {
			continue
		}
		marker, ok := value.(map[string]any)
		if !ok || marker["index"] != float64(index) {
			return errors.New("root_domain_network_outbound_invalid")
		}
		domainText, domainOK := marker["domainText"].(string)
		networkText, networkOK := marker["networkText"].(string)
		outbound, outboundOK := marker["outbound"].(string)
		invert, invertOK := marker["invert"].(bool)
		if !domainOK || !networkOK || !outboundOK || !invertOK {
			return errors.New("root_domain_network_outbound_invalid")
		}
		domains, suffixes, keywords, err := parseRootDNSDomainMatchers(domainText)
		if err != nil {
			return errors.New("root_domain_network_outbound_invalid")
		}
		networks, err := parseRootRouteNetworkRejectNetworks(networkText)
		if err != nil {
			return errors.New("root_domain_network_outbound_invalid")
		}
		generated := map[string]any{"network": networks, "outbound": outbound}
		if len(domains) > 0 {
			generated["domain"] = domains
		}
		if len(suffixes) > 0 {
			generated["domain_suffix"] = suffixes
		}
		if len(keywords) > 0 {
			generated["domain_keyword"] = keywords
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
