// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package module

import (
	"errors"
	"math"
)

const rootAppUIDRuleSetOutboundMarker = "_wanbox_root_app_uid_ruleset_outbound_v83"

type RootRouteAppUIDRuleSetOutboundRoutesSpec struct {
	Rules []RootRouteAppUIDRuleSetOutboundRuleSpec `json:"rules"`
}
type RootRouteAppUIDRuleSetOutboundRuleSpec struct {
	Index       int      `json:"index"`
	RuleID      int64    `json:"ruleId"`
	Packages    []string `json:"packages"`
	UIDs        []int    `json:"uids"`
	RuleSetTags []string `json:"ruleSetTags"`
	Outbound    string   `json:"outbound"`
	Invert      bool     `json:"invert"`
}

func hasRootAppUIDRuleSetOutboundMarker(config map[string]any) bool {
	rules, _ := nestedMap(config, "route")["rules"].([]any)
	for _, raw := range rules {
		if rule, ok := raw.(map[string]any); ok {
			if _, exists := rule[rootAppUIDRuleSetOutboundMarker]; exists {
				return true
			}
		}
	}
	return false
}

func validateRootAppUIDRuleSetOutboundRoutes(spec *RootRouteAppUIDRuleSetOutboundRoutesSpec, config map[string]any, snapshot Snapshot) error {
	if spec == nil || len(spec.Rules) == 0 || len(spec.Rules) > 4096 {
		return errors.New("root_app_uid_ruleset_outbound_invalid")
	}
	rules, ok := nestedMap(config, "route")["rules"].([]any)
	if !ok {
		return errors.New("root_app_uid_ruleset_outbound_invalid")
	}
	migrated := migratedRootRemoteRuleSetTags(config)
	found := 0
	ids := map[int64]bool{}
	for index, raw := range rules {
		rule, ok := raw.(map[string]any)
		if !ok {
			continue
		}
		value, exists := rule[rootAppUIDRuleSetOutboundMarker]
		if !exists {
			continue
		}
		marker, ok := value.(map[string]any)
		if !ok || len(rule) != 1 || len(marker) != 7 || found >= len(spec.Rules) {
			return errors.New("root_app_uid_ruleset_outbound_invalid")
		}
		idx, idxOK := marker["index"].(float64)
		id, idOK := marker["ruleId"].(float64)
		packages, packagesOK := marker["packages"].([]any)
		uids, uidsOK := marker["uids"].([]any)
		tags, tagsOK := marker["ruleSetTags"].([]any)
		outbound, outboundOK := marker["outbound"].(string)
		invert, invertOK := marker["invert"].(bool)
		item := spec.Rules[found]
		if !idxOK || math.IsNaN(idx) || math.IsInf(idx, 0) || math.Trunc(idx) != idx || idx != float64(index) || item.Index != index ||
			!idOK || math.IsNaN(id) || math.IsInf(id, 0) || math.Trunc(id) != id || id < 1 || id > 9007199254740991 || int64(id) != item.RuleID || ids[item.RuleID] ||
			!packagesOK || len(packages) == 0 || len(packages) > 256 || len(packages) != len(item.Packages) || !uidsOK || len(uids) > 4096 || len(uids) != len(item.UIDs) ||
			!tagsOK || len(tags) == 0 || len(tags) > 256 || len(tags) != len(item.RuleSetTags) || !outboundOK || outbound != item.Outbound || !invertOK || invert != item.Invert ||
			!validRootRouteAppUIDDomainPackageSet(item.Packages) || len(item.UIDs) > 4096 || !validRootRouteNetworkOutboundTag(outbound) || !hasRootOutboundTag(config, snapshot, outbound) {
			return errors.New("root_app_uid_ruleset_outbound_invalid")
		}
		ids[item.RuleID] = true
		for i, v := range packages {
			name, ok := v.(string)
			if !ok || name != item.Packages[i] || !validRootRouteAppUIDPackage(name) {
				return errors.New("root_app_uid_ruleset_outbound_invalid")
			}
		}
		seenUID := map[int]bool{}
		for i, v := range uids {
			n, ok := v.(float64)
			if !ok || math.Trunc(n) != n || n < 0 || n != float64(item.UIDs[i]) || seenUID[item.UIDs[i]] {
				return errors.New("root_app_uid_ruleset_outbound_invalid")
			}
			seenUID[item.UIDs[i]] = true
		}
		for i, v := range tags {
			tag, ok := v.(string)
			if !ok || !validRootRouteNetworkOutboundTag(tag) || tag != item.RuleSetTags[i] || !migrated[tag] {
				return errors.New("root_app_uid_ruleset_outbound_invalid")
			}
		}
		found++
	}
	if found != len(spec.Rules) {
		return errors.New("root_app_uid_ruleset_outbound_invalid")
	}
	return nil
}

func materializeRootAppUIDRuleSetOutboundRoutes(route map[string]any, spec *RootRouteAppUIDRuleSetOutboundRoutesSpec) error {
	if spec == nil || len(spec.Rules) == 0 {
		return errors.New("root_app_uid_ruleset_outbound_invalid")
	}
	rules, ok := route["rules"].([]any)
	if !ok {
		return errors.New("root_app_uid_ruleset_outbound_invalid")
	}
	found := 0
	for index, raw := range rules {
		rule, ok := raw.(map[string]any)
		if !ok {
			continue
		}
		value, exists := rule[rootAppUIDRuleSetOutboundMarker]
		if !exists {
			continue
		}
		marker, ok := value.(map[string]any)
		if !ok || len(rule) != 1 || len(marker) != 7 || found >= len(spec.Rules) {
			return errors.New("root_app_uid_ruleset_outbound_invalid")
		}
		item := spec.Rules[found]
		if marker["index"] != float64(item.Index) {
			return errors.New("root_app_uid_ruleset_outbound_invalid")
		}
		generated := map[string]any{"package_name": append([]string(nil), item.Packages...), "rule_set": append([]string(nil), item.RuleSetTags...), "outbound": item.Outbound}
		if len(item.UIDs) > 0 {
			generated["user_id"] = append([]int(nil), item.UIDs...)
		}
		if item.Invert {
			generated["invert"] = true
		}
		rules[index] = generated
		found++
	}
	if found != len(spec.Rules) {
		return errors.New("root_app_uid_ruleset_outbound_invalid")
	}
	route["rules"] = rules
	return nil
}
