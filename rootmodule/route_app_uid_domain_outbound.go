// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package module

import (
	"errors"
	"math"
	"strings"
)

const rootAppUIDDomainOutboundRouteMarker = "_wanbox_root_app_uid_domain_outbound_v81"

type RootRouteAppUIDDomainOutboundRoutesSpec struct {
	Rules []RootRouteAppUIDDomainOutboundRuleSpec `json:"rules"`
}

type RootRouteAppUIDDomainOutboundRuleSpec struct {
	Index      int      `json:"index"`
	RuleID     int64    `json:"ruleId"`
	Packages   []string `json:"packages"`
	UIDs       []int    `json:"uids"`
	DomainText string   `json:"domainText"`
	Outbound   string   `json:"outbound"`
	Invert     bool     `json:"invert"`
}

func hasRootAppUIDDomainOutboundRouteMarker(config map[string]any) bool {
	rules, _ := nestedMap(config, "route")["rules"].([]any)
	for _, raw := range rules {
		if rule, ok := raw.(map[string]any); ok {
			if _, exists := rule[rootAppUIDDomainOutboundRouteMarker]; exists {
				return true
			}
		}
	}
	return false
}

func validateRootAppUIDDomainOutboundRoutes(spec *RootRouteAppUIDDomainOutboundRoutesSpec, config map[string]any, snapshot Snapshot) error {
	if spec == nil || len(spec.Rules) == 0 || len(spec.Rules) > 4096 {
		return errors.New("root_app_uid_domain_outbound_invalid")
	}
	rules, ok := nestedMap(config, "route")["rules"].([]any)
	if !ok {
		return errors.New("root_app_uid_domain_outbound_invalid")
	}
	seenIDs := map[int64]bool{}
	found := 0
	for index, raw := range rules {
		rule, ok := raw.(map[string]any)
		if !ok {
			continue
		}
		value, exists := rule[rootAppUIDDomainOutboundRouteMarker]
		if !exists {
			continue
		}
		marker, ok := value.(map[string]any)
		if !ok || len(rule) != 1 || len(marker) != 7 || found >= len(spec.Rules) {
			return errors.New("root_app_uid_domain_outbound_invalid")
		}
		idx, idxOK := marker["index"].(float64)
		id, idOK := marker["ruleId"].(float64)
		packages, packagesOK := marker["packages"].([]any)
		uids, uidsOK := marker["uids"].([]any)
		domainText, domainOK := marker["domainText"].(string)
		outbound, outboundOK := marker["outbound"].(string)
		invert, invertOK := marker["invert"].(bool)
		item := spec.Rules[found]
		if !idxOK || math.IsNaN(idx) || math.IsInf(idx, 0) || math.Trunc(idx) != idx || idx != float64(index) || item.Index != index ||
			!idOK || math.IsNaN(id) || math.IsInf(id, 0) || math.Trunc(id) != id || id < 1 || id > 9007199254740991 || int64(id) != item.RuleID || seenIDs[item.RuleID] ||
			!packagesOK || len(packages) == 0 || len(packages) > 256 || len(packages) != len(item.Packages) ||
			!uidsOK || len(uids) > 4096 || len(uids) != len(item.UIDs) || !domainOK || !outboundOK || !invertOK ||
			strings.TrimSpace(outbound) != outbound || outbound == "" || outbound == "block" || !validRootRouteNetworkOutboundTag(outbound) || !hasRootOutboundTag(config, snapshot, outbound) ||
			domainText != item.DomainText || outbound != item.Outbound || invert != item.Invert || !validRootRouteAppUIDDomainPackageSet(item.Packages) ||
			len(item.UIDs) > 4096 {
			return errors.New("root_app_uid_domain_outbound_invalid")
		}
		seenIDs[item.RuleID] = true
		seenPackages := map[string]bool{}
		for i, value := range packages {
			name, ok := value.(string)
			if !ok || name != item.Packages[i] || !validRootRouteAppUIDPackage(name) || seenPackages[name] {
				return errors.New("root_app_uid_domain_outbound_invalid")
			}
			seenPackages[name] = true
		}
		seenUIDs := map[int]bool{}
		for i, value := range uids {
			n, ok := value.(float64)
			if !ok || math.Trunc(n) != n || n < 0 || n != float64(item.UIDs[i]) || seenUIDs[item.UIDs[i]] {
				return errors.New("root_app_uid_domain_outbound_invalid")
			}
			seenUIDs[item.UIDs[i]] = true
		}
		if _, _, _, err := parseRootDNSDomainMatchers(domainText); err != nil {
			return errors.New("root_app_uid_domain_outbound_invalid")
		}
		found++
	}
	if found != len(spec.Rules) {
		return errors.New("root_app_uid_domain_outbound_invalid")
	}
	return nil
}

func validRootRouteAppUIDDomainPackageSet(packages []string) bool {
	if len(packages) == 0 || len(packages) > 256 {
		return false
	}
	seen := map[string]bool{}
	for _, name := range packages {
		if !validRootRouteAppUIDPackage(name) || seen[name] {
			return false
		}
		seen[name] = true
	}
	return true
}

func materializeRootAppUIDDomainOutboundRoutes(route map[string]any, spec *RootRouteAppUIDDomainOutboundRoutesSpec) error {
	if spec == nil || len(spec.Rules) == 0 {
		return errors.New("root_app_uid_domain_outbound_invalid")
	}
	rules, ok := route["rules"].([]any)
	if !ok {
		return errors.New("root_app_uid_domain_outbound_invalid")
	}
	found := 0
	for index, raw := range rules {
		rule, ok := raw.(map[string]any)
		if !ok {
			continue
		}
		value, exists := rule[rootAppUIDDomainOutboundRouteMarker]
		if !exists {
			continue
		}
		marker, ok := value.(map[string]any)
		if !ok || len(rule) != 1 || len(marker) != 7 || found >= len(spec.Rules) {
			return errors.New("root_app_uid_domain_outbound_invalid")
		}
		item := spec.Rules[found]
		// Earlier Root migrations can insert rules before this marker. Check its
		// original projected index against the validated spec, then replace it
		// at its current slice position to preserve final ordering.
		if marker["index"] != float64(item.Index) {
			return errors.New("root_app_uid_domain_outbound_invalid")
		}
		domains, suffixes, keywords, err := parseRootDNSDomainMatchers(item.DomainText)
		if err != nil {
			return errors.New("root_app_uid_domain_outbound_invalid")
		}
		generated := map[string]any{"package_name": append([]string(nil), item.Packages...), "outbound": item.Outbound}
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
		return errors.New("root_app_uid_domain_outbound_invalid")
	}
	route["rules"] = rules
	return nil
}
