// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package module

import (
	"errors"
)

const rootRemoteRuleSetDNSEffectMarker = "_wanbox_root_remote_ruleset_dns_effect_v56"

func hasRootRemoteRuleSetDNSEffectMarker(config map[string]any) bool {
	rules, _ := nestedMap(config, "dns")["rules"].([]any)
	for _, raw := range rules {
		if rule, ok := raw.(map[string]any); ok {
			if _, exists := rule[rootRemoteRuleSetDNSEffectMarker]; exists {
				return true
			}
		}
	}
	return false
}

func rootRemoteRuleSetDNSEffectValue(rule map[string]any) (map[string]any, bool, error) {
	value, exists := rule[rootRemoteRuleSetDNSEffectMarker]
	if !exists {
		return nil, false, nil
	}
	marker, ok := value.(map[string]any)
	if !ok || len(rule) != 1 || len(marker) != 2 {
		return nil, true, errors.New("root_remote_ruleset_dns_effect_invalid")
	}
	return marker, true, nil
}

func validateRootRemoteRuleSetDNSEffects(config map[string]any, snapshot Snapshot) error {
	if !hasRootRemoteRuleSetDNSEffectMarker(config) {
		return nil
	}
	if snapshot.RootInbound == nil || snapshot.RootInbound.Backend != "tun" {
		return errors.New("root_remote_ruleset_dns_effect_invalid")
	}
	rules, err := rootDNSRules(config)
	if err != nil {
		return err
	}
	known := migratedRootRemoteRuleSetTags(config)
	for _, raw := range rules {
		rule, ok := raw.(map[string]any)
		if !ok {
			continue
		}
		marker, exists, err := rootRemoteRuleSetDNSEffectValue(rule)
		if err != nil {
			return err
		}
		if !exists {
			continue
		}
		tag, tagOK := marker["tag"].(string)
		kind, kindOK := marker["kind"].(string)
		if !tagOK || tag == "" || !known[tag] || !kindOK {
			return errors.New("root_remote_ruleset_dns_effect_invalid")
		}
		switch kind {
		case "direct":
			if !hasDNSServer(config, "dns-direct") {
				return errors.New("root_remote_ruleset_dns_effect_invalid")
			}
		case "reject":
		case "proxy":
			if !hasDNSServer(config, "dns-remote") {
				return errors.New("root_remote_ruleset_dns_effect_invalid")
			}
		case "fake":
			if !hasDNSServer(config, "dns-fake") {
				return errors.New("root_remote_ruleset_dns_effect_invalid")
			}
		default:
			return errors.New("root_remote_ruleset_dns_effect_invalid")
		}
	}
	return nil
}

// Root generates the simple DNS rule_set effect in the original array slot. The
// projected tag must have a Root-generated remote binary definition (checked above).
func materializeRootRemoteRuleSetDNSEffects(config map[string]any) error {
	rules, err := rootDNSRules(config)
	if err != nil {
		return err
	}
	for index, raw := range rules {
		rule, ok := raw.(map[string]any)
		if !ok {
			continue
		}
		marker, exists, err := rootRemoteRuleSetDNSEffectValue(rule)
		if err != nil {
			return err
		}
		if !exists {
			continue
		}
		tag, tagOK := marker["tag"].(string)
		kind, kindOK := marker["kind"].(string)
		if !tagOK || tag == "" || !kindOK {
			return errors.New("root_remote_ruleset_dns_effect_invalid")
		}
		generated := map[string]any{"rule_set": []string{tag}}
		switch kind {
		case "direct":
			generated["server"] = "dns-direct"
		case "reject":
			generated["action"] = "reject"
		case "proxy":
			generated["server"] = "dns-remote"
		case "fake":
			generated["server"] = "dns-fake"
			generated["inbound"] = []string{"tun-in"}
			generated["query_type"] = []string{"A", "AAAA"}
		default:
			return errors.New("root_remote_ruleset_dns_effect_invalid")
		}
		rules[index] = generated
	}
	return nil
}
