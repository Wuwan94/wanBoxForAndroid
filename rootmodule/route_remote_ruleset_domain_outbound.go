// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package module

import (
	"errors"
	"math"
	"strings"
)

const rootRemoteRuleSetDomainOutboundRouteMarker = "_wanbox_root_remote_ruleset_domain_outbound_v68"

func rootRemoteRuleSetDomainOutboundMarker(rule map[string]any) (map[string]any, bool, error) {
	value, exists := rule[rootRemoteRuleSetDomainOutboundRouteMarker]
	if !exists {
		return nil, false, nil
	}
	marker, ok := value.(map[string]any)
	if !ok || len(rule) != 1 || len(marker) != 7 {
		return nil, true, errors.New("root_remote_ruleset_domain_outbound_invalid")
	}
	for _, key := range []string{"index", "tags", "domains", "suffixes", "keywords", "outbound", "invert"} {
		if _, ok := marker[key]; !ok {
			return nil, true, errors.New("root_remote_ruleset_domain_outbound_invalid")
		}
	}
	return marker, true, nil
}

func hasRootRemoteRuleSetDomainOutboundRouteMarker(config map[string]any) bool {
	rules, _ := nestedMap(config, "route")["rules"].([]any)
	for _, raw := range rules {
		if rule, ok := raw.(map[string]any); ok {
			if _, exists := rule[rootRemoteRuleSetDomainOutboundRouteMarker]; exists {
				return true
			}
		}
	}
	return false
}

func validateRootRemoteRuleSetDomainOutboundRoutes(config map[string]any, snapshot Snapshot) error {
	rules, ok := nestedMap(config, "route")["rules"].([]any)
	if !ok {
		return errors.New("root_remote_ruleset_domain_outbound_invalid")
	}
	migrated := migratedRootRemoteRuleSetTags(config)
	for index, raw := range rules {
		rule, ok := raw.(map[string]any)
		if !ok {
			continue
		}
		marker, exists, err := rootRemoteRuleSetDomainOutboundMarker(rule)
		if err != nil {
			return err
		}
		if !exists {
			continue
		}
		idx, idxOK := marker["index"].(float64)
		tags, tagsOK := marker["tags"].([]any)
		domains, domainsOK := marker["domains"].([]any)
		suffixes, suffixesOK := marker["suffixes"].([]any)
		keywords, keywordsOK := marker["keywords"].([]any)
		outbound, outboundOK := marker["outbound"].(string)
		_, invertOK := marker["invert"].(bool)
		if !idxOK || math.IsNaN(idx) || math.IsInf(idx, 0) || math.Trunc(idx) != idx || idx != float64(index) ||
			!tagsOK || len(tags) == 0 || len(tags) > 256 || !domainsOK || !suffixesOK || !keywordsOK ||
			len(domains)+len(suffixes)+len(keywords) == 0 || len(domains)+len(suffixes)+len(keywords) > 256 ||
			!outboundOK || strings.TrimSpace(outbound) != outbound || outbound == "" || len(outbound) > 256 ||
			strings.ContainsAny(outbound, "\\/\x00\r\n") || !invertOK ||
			!validRootRouteNetworkOutboundTag(outbound) || !hasRootOutboundTag(config, snapshot, outbound) ||
			snapshot.RootInbound == nil || snapshot.RootInbound.Backend != "tun" {
			return errors.New("root_remote_ruleset_domain_outbound_invalid")
		}
		seenTags := map[string]bool{}
		for _, rawTag := range tags {
			tag, ok := rawTag.(string)
			if !ok || !validRootRouteNetworkOutboundTag(tag) || seenTags[tag] || !migrated[tag] {
				return errors.New("root_remote_ruleset_domain_outbound_invalid")
			}
			seenTags[tag] = true
		}
		if !validRootDomainField(domains, "full:") || !validRootDomainField(suffixes, "domain:") || !validRootDomainField(keywords, "keyword:") {
			return errors.New("root_remote_ruleset_domain_outbound_invalid")
		}
	}
	return nil
}

func validRootDomainField(values []any, prefix string) bool {
	for _, raw := range values {
		value, ok := raw.(string)
		if !ok || strings.TrimSpace(value) != value || value == "" || strings.ContainsAny(value, ",\n") {
			return false
		}
		if _, _, _, err := parseRootDNSDomainMatchers(prefix + value); err != nil {
			return false
		}
	}
	return true
}

func materializeRootRemoteRuleSetDomainOutboundRoutes(config map[string]any) error {
	route := nestedMap(config, "route")
	rules, ok := route["rules"].([]any)
	if !ok {
		return errors.New("root_remote_ruleset_domain_outbound_invalid")
	}
	for index, raw := range rules {
		rule, ok := raw.(map[string]any)
		if !ok {
			continue
		}
		marker, exists, err := rootRemoteRuleSetDomainOutboundMarker(rule)
		if err != nil {
			return err
		}
		if !exists {
			continue
		}
		idx, idxOK := marker["index"].(float64)
		tags, tagsOK := marker["tags"].([]any)
		domains, domainsOK := marker["domains"].([]any)
		suffixes, suffixesOK := marker["suffixes"].([]any)
		keywords, keywordsOK := marker["keywords"].([]any)
		outbound, outboundOK := marker["outbound"].(string)
		invert, invertOK := marker["invert"].(bool)
		if !idxOK || idx != float64(index) || !tagsOK || !domainsOK || !suffixesOK || !keywordsOK || !outboundOK || !invertOK {
			return errors.New("root_remote_ruleset_domain_outbound_invalid")
		}
		generated := map[string]any{"rule_set": tags, "outbound": outbound}
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
