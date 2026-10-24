// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package module

import (
	"errors"
	"math"
)

const rootRemoteRuleSetDomainRejectRouteMarker = "_wanbox_root_remote_ruleset_domain_reject_v74"

func rootRemoteRuleSetDomainRejectMarker(rule map[string]any) (map[string]any, bool, error) {
	value, exists := rule[rootRemoteRuleSetDomainRejectRouteMarker]
	if !exists {
		return nil, false, nil
	}
	marker, ok := value.(map[string]any)
	if !ok || len(rule) != 1 || len(marker) != 6 {
		return nil, true, errors.New("root_remote_ruleset_domain_reject_invalid")
	}
	for _, key := range []string{"index", "tags", "domains", "suffixes", "keywords", "invert"} {
		if _, ok := marker[key]; !ok {
			return nil, true, errors.New("root_remote_ruleset_domain_reject_invalid")
		}
	}
	return marker, true, nil
}

func hasRootRemoteRuleSetDomainRejectRouteMarker(config map[string]any) bool {
	rules, _ := nestedMap(config, "route")["rules"].([]any)
	for _, raw := range rules {
		if rule, ok := raw.(map[string]any); ok {
			if _, exists := rule[rootRemoteRuleSetDomainRejectRouteMarker]; exists {
				return true
			}
		}
	}
	return false
}

func validateRootRemoteRuleSetDomainRejectRoutes(config map[string]any, snapshot Snapshot) error {
	rules, ok := nestedMap(config, "route")["rules"].([]any)
	if !ok {
		return errors.New("root_remote_ruleset_domain_reject_invalid")
	}
	migrated := migratedRootRemoteRuleSetTags(config)
	for index, raw := range rules {
		rule, ok := raw.(map[string]any)
		if !ok {
			continue
		}
		marker, exists, err := rootRemoteRuleSetDomainRejectMarker(rule)
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
		_, invertOK := marker["invert"].(bool)
		if !idxOK || math.IsNaN(idx) || math.IsInf(idx, 0) || math.Trunc(idx) != idx || idx != float64(index) ||
			!tagsOK || len(tags) == 0 || len(tags) > 256 || !domainsOK || !suffixesOK || !keywordsOK ||
			len(domains)+len(suffixes)+len(keywords) == 0 || len(domains)+len(suffixes)+len(keywords) > 256 || !invertOK ||
			snapshot.RootInbound == nil || snapshot.RootInbound.Backend != "tun" {
			return errors.New("root_remote_ruleset_domain_reject_invalid")
		}
		seenTags := map[string]bool{}
		for _, rawTag := range tags {
			tag, ok := rawTag.(string)
			if !ok || !validRootRouteNetworkOutboundTag(tag) || seenTags[tag] || !migrated[tag] {
				return errors.New("root_remote_ruleset_domain_reject_invalid")
			}
			seenTags[tag] = true
		}
		if !validRootDomainField(domains, "full:") || !validRootDomainField(suffixes, "domain:") || !validRootDomainField(keywords, "keyword:") {
			return errors.New("root_remote_ruleset_domain_reject_invalid")
		}
	}
	return nil
}

func materializeRootRemoteRuleSetDomainRejectRoutes(config map[string]any) error {
	route := nestedMap(config, "route")
	rules, ok := route["rules"].([]any)
	if !ok {
		return errors.New("root_remote_ruleset_domain_reject_invalid")
	}
	for index, raw := range rules {
		rule, ok := raw.(map[string]any)
		if !ok {
			continue
		}
		marker, exists, err := rootRemoteRuleSetDomainRejectMarker(rule)
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
		invert, invertOK := marker["invert"].(bool)
		if !idxOK || idx != float64(index) || !tagsOK || !domainsOK || !suffixesOK || !keywordsOK || !invertOK {
			return errors.New("root_remote_ruleset_domain_reject_invalid")
		}
		generated := map[string]any{"rule_set": tags, "action": "reject"}
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
