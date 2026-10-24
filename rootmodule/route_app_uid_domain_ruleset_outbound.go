// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package module

import (
	"errors"
	"math"
	"strings"
)

const rootAppUIDDomainRuleSetOutboundMarker = "_wanbox_root_app_uid_domain_ruleset_outbound_v84"

type RootRouteAppUIDDomainRuleSetOutboundRoutesSpec struct {
	Rules []RootRouteAppUIDDomainRuleSetOutboundRuleSpec `json:"rules"`
}
type RootRouteAppUIDDomainRuleSetOutboundRuleSpec struct {
	Index        int      `json:"index"`
	RuleID       int64    `json:"ruleId"`
	Packages     []string `json:"packages"`
	UIDs         []int    `json:"uids"`
	DomainText   string   `json:"domainText"`
	RuleSetTags  []string `json:"ruleSetTags"`
	RuleSetKinds []string `json:"ruleSetKinds"`
	Outbound     string   `json:"outbound"`
	Invert       bool     `json:"invert"`
}

func hasRootAppUIDDomainRuleSetOutboundMarker(config map[string]any) bool {
	rules, _ := nestedMap(config, "route")["rules"].([]any)
	for _, raw := range rules {
		if rule, ok := raw.(map[string]any); ok {
			if _, exists := rule[rootAppUIDDomainRuleSetOutboundMarker]; exists {
				return true
			}
		}
	}
	return false
}

func validateRootAppUIDDomainRuleSetOutboundRoutes(spec *RootRouteAppUIDDomainRuleSetOutboundRoutesSpec, config map[string]any, snapshot Snapshot) error {
	if spec == nil || len(spec.Rules) == 0 || len(spec.Rules) > 4096 {
		return errors.New("root_app_uid_domain_ruleset_outbound_invalid")
	}
	rules, ok := nestedMap(config, "route")["rules"].([]any)
	if !ok {
		return errors.New("root_app_uid_domain_ruleset_outbound_invalid")
	}
	migrated := migratedRootRemoteRuleSetTags(config)
	seenIDs := map[int64]bool{}
	found := 0
	for index, raw := range rules {
		rule, ok := raw.(map[string]any)
		if !ok {
			continue
		}
		value, exists := rule[rootAppUIDDomainRuleSetOutboundMarker]
		if !exists {
			continue
		}
		marker, ok := value.(map[string]any)
		if !ok || len(rule) != 1 || len(marker) != 9 || found >= len(spec.Rules) {
			return errors.New("root_app_uid_domain_ruleset_outbound_invalid")
		}
		idx, idxOK := marker["index"].(float64)
		id, idOK := marker["ruleId"].(float64)
		packages, packagesOK := marker["packages"].([]any)
		uids, uidsOK := marker["uids"].([]any)
		domainText, domainOK := marker["domainText"].(string)
		tags, tagsOK := marker["ruleSetTags"].([]any)
		kinds, kindsOK := marker["ruleSetKinds"].([]any)
		outbound, outboundOK := marker["outbound"].(string)
		invert, invertOK := marker["invert"].(bool)
		item := spec.Rules[found]
		if !idxOK || math.IsNaN(idx) || math.IsInf(idx, 0) || math.Trunc(idx) != idx || idx != float64(index) || item.Index != index ||
			!idOK || math.IsNaN(id) || math.IsInf(id, 0) || math.Trunc(id) != id || id < 1 || id > 9007199254740991 || int64(id) != item.RuleID || seenIDs[item.RuleID] ||
			!packagesOK || len(packages) == 0 || len(packages) > 256 || len(packages) != len(item.Packages) ||
			!uidsOK || len(uids) > 4096 || len(uids) != len(item.UIDs) ||
			!domainOK || domainText == "" || domainText != item.DomainText ||
			!tagsOK || len(tags) == 0 || len(tags) > 256 || len(tags) != len(item.RuleSetTags) ||
			!kindsOK || len(kinds) != len(item.RuleSetKinds) || len(kinds) != len(tags) ||
			!outboundOK || outbound != item.Outbound || !invertOK || invert != item.Invert ||
			strings.TrimSpace(outbound) != outbound || outbound == "" || outbound == "block" || !validRootRouteNetworkOutboundTag(outbound) || !hasRootOutboundTag(config, snapshot, outbound) ||
			!validRootRouteAppUIDDomainPackageSet(item.Packages) || len(item.UIDs) > 4096 ||
			!validRootAppUIDDomainRuleSetKinds(item.RuleSetKinds, item.RuleSetTags, migrated) {
			return errors.New("root_app_uid_domain_ruleset_outbound_invalid")
		}
		seenIDs[item.RuleID] = true
		for i, v := range packages {
			name, ok := v.(string)
			if !ok || name != item.Packages[i] || !validRootRouteAppUIDPackage(name) {
				return errors.New("root_app_uid_domain_ruleset_outbound_invalid")
			}
		}
		seenUIDs := map[int]bool{}
		for i, v := range uids {
			n, ok := v.(float64)
			if !ok || math.Trunc(n) != n || n < 0 || n != float64(item.UIDs[i]) || seenUIDs[item.UIDs[i]] {
				return errors.New("root_app_uid_domain_ruleset_outbound_invalid")
			}
			seenUIDs[item.UIDs[i]] = true
		}
		for i, v := range tags {
			tag, ok := v.(string)
			if !ok || tag != item.RuleSetTags[i] || !migrated[tag] || !validRootRouteNetworkOutboundTag(tag) {
				return errors.New("root_app_uid_domain_ruleset_outbound_invalid")
			}
		}
		for i, v := range kinds {
			kind, ok := v.(string)
			if !ok || kind != item.RuleSetKinds[i] || kind != "domain" {
				return errors.New("root_app_uid_domain_ruleset_outbound_invalid")
			}
		}
		if _, _, _, err := parseRootDNSDomainMatchers(domainText); err != nil {
			return errors.New("root_app_uid_domain_ruleset_outbound_invalid")
		}
		found++
	}
	if found != len(spec.Rules) {
		return errors.New("root_app_uid_domain_ruleset_outbound_invalid")
	}
	return nil
}

func allRootAppUIDDomainRuleSetKinds(kinds []string) bool {
	if len(kinds) == 0 || len(kinds) > 256 {
		return false
	}
	for _, kind := range kinds {
		if kind != "domain" {
			return false
		}
	}
	return true
}

func validRootAppUIDDomainRuleSetKinds(kinds, tags []string, migrated map[string]bool) bool {
	if len(kinds) != len(tags) || !allRootAppUIDDomainRuleSetKinds(kinds) {
		return false
	}
	for _, tag := range tags {
		if !migrated[tag] {
			return false
		}
	}
	return true
}

func materializeRootAppUIDDomainRuleSetOutboundRoutes(route map[string]any, spec *RootRouteAppUIDDomainRuleSetOutboundRoutesSpec) error {
	if spec == nil || len(spec.Rules) == 0 {
		return errors.New("root_app_uid_domain_ruleset_outbound_invalid")
	}
	rules, ok := route["rules"].([]any)
	if !ok {
		return errors.New("root_app_uid_domain_ruleset_outbound_invalid")
	}
	found := 0
	for index, raw := range rules {
		rule, ok := raw.(map[string]any)
		if !ok {
			continue
		}
		value, exists := rule[rootAppUIDDomainRuleSetOutboundMarker]
		if !exists {
			continue
		}
		marker, ok := value.(map[string]any)
		if !ok || len(rule) != 1 || len(marker) != 9 || found >= len(spec.Rules) {
			return errors.New("root_app_uid_domain_ruleset_outbound_invalid")
		}
		item := spec.Rules[found]
		if marker["index"] != float64(item.Index) || len(item.RuleSetKinds) == 0 || len(item.RuleSetKinds) != len(item.RuleSetTags) || !allRootAppUIDDomainRuleSetKinds(item.RuleSetKinds) {
			return errors.New("root_app_uid_domain_ruleset_outbound_invalid")
		}
		domains, suffixes, keywords, err := parseRootDNSDomainMatchers(item.DomainText)
		if err != nil {
			return errors.New("root_app_uid_domain_ruleset_outbound_invalid")
		}
		generated := map[string]any{"package_name": append([]string(nil), item.Packages...), "rule_set": append([]string(nil), item.RuleSetTags...), "outbound": item.Outbound}
		if len(item.UIDs) > 0 {
			generated["user_id"] = append([]int(nil), item.UIDs...)
		}
		if len(domains) > 0 {
			generated["domain"] = domains
		}
		if len(suffixes) > 0 {
			generated["domain_suffix"] = suffixes
		}
		if len(keywords) > 0 {
			generated["domain_keyword"] = keywords
		}
		if item.Invert {
			generated["invert"] = true
		}
		rules[index] = generated
		found++
	}
	if found != len(spec.Rules) {
		return errors.New("root_app_uid_domain_ruleset_outbound_invalid")
	}
	route["rules"] = rules
	return nil
}
