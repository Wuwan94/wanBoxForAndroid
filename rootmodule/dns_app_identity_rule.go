// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package module

import (
	"errors"
	"math"
)

const rootAppIdentityDNSRuleMarker = "_wanbox_root_app_identity_dns_rule_v79"

func appIdentityDNSMarker(rule map[string]any) (map[string]any, bool, error) {
	value, exists := rule[rootAppIdentityDNSRuleMarker]
	if !exists {
		return nil, false, nil
	}
	marker, ok := value.(map[string]any)
	if !ok || len(rule) != 1 || len(marker) != 5 {
		return nil, true, errors.New("root_app_identity_dns_invalid")
	}
	for _, key := range []string{"ruleId", "packages", "uids", "kind", "fakeDns"} {
		if _, ok := marker[key]; !ok {
			return nil, true, errors.New("root_app_identity_dns_invalid")
		}
	}
	return marker, true, nil
}

func hasRootAppIdentityDNSRuleMarker(config map[string]any) bool {
	dns, _ := config["dns"].(map[string]any)
	rules, _ := dns["rules"].([]any)
	for _, raw := range rules {
		if rule, ok := raw.(map[string]any); ok {
			if _, exists := rule[rootAppIdentityDNSRuleMarker]; exists {
				return true
			}
		}
	}
	return false
}

func validateRootAppIdentityDNSRules(config map[string]any, snapshot Snapshot) error {
	rules, err := rootDNSRules(config)
	if err != nil {
		return err
	}
	seen := map[int64]bool{}
	for _, raw := range rules {
		rule, ok := raw.(map[string]any)
		if !ok {
			continue
		}
		marker, exists, err := appIdentityDNSMarker(rule)
		if err != nil {
			return err
		}
		if !exists {
			continue
		}
		id, idOK := marker["ruleId"].(float64)
		packages, packagesOK := marker["packages"].([]any)
		uids, uidsOK := marker["uids"].([]any)
		kind, kindOK := marker["kind"].(string)
		fake, fakeOK := marker["fakeDns"].(bool)
		if !idOK || math.IsNaN(id) || math.IsInf(id, 0) || math.Trunc(id) != id || id < 1 || id > float64(maxRootDNSRuleID) || seen[int64(id)] ||
			!packagesOK || len(packages) == 0 || len(packages) > 256 || !uidsOK || len(uids) > 4096 || !kindOK || !fakeOK ||
			(kind != "direct" && kind != "reject" && kind != "proxy") || fake && kind != "proxy" {
			return errors.New("root_app_identity_dns_invalid")
		}
		seen[int64(id)] = true
		seenPackages := map[string]bool{}
		for _, rawPackage := range packages {
			packageName, ok := rawPackage.(string)
			if !ok || !validRootRouteAppUIDPackage(packageName) || seenPackages[packageName] {
				return errors.New("root_app_identity_dns_invalid")
			}
			seenPackages[packageName] = true
		}
		for _, rawUID := range uids {
			uid, ok := rawUID.(float64)
			if !ok || math.IsNaN(uid) || math.IsInf(uid, 0) || math.Trunc(uid) != uid || uid < 0 || uid > math.MaxInt32 {
				return errors.New("root_app_identity_dns_invalid")
			}
		}
		if fake && (snapshot.RootInbound == nil || snapshot.RootInbound.Backend != "tun" || !hasDNSServer(config, "dns-fake")) ||
			kind == "direct" && !hasDNSServer(config, "dns-direct") && (snapshot.RootDNSDirectServer == nil || snapshot.RootDNSDirectServer.Tag != "dns-direct") || kind == "proxy" && !fake && !hasDNSServer(config, "dns-remote") {
			return errors.New("root_app_identity_dns_invalid")
		}
	}
	return nil
}

// The package-only DNS side effect is reconstructed in the same position; the
// corresponding App-generated route rule remains untouched by this migration.
func materializeRootAppIdentityDNSRules(config map[string]any, snapshot Snapshot) error {
	if err := validateRootAppIdentityDNSRules(config, snapshot); err != nil {
		return err
	}
	rules, _ := rootDNSRules(config)
	for index, raw := range rules {
		rule, ok := raw.(map[string]any)
		if !ok {
			continue
		}
		marker, exists, err := appIdentityDNSMarker(rule)
		if err != nil {
			return err
		}
		if !exists {
			continue
		}
		packages, _ := marker["packages"].([]any)
		uids, _ := marker["uids"].([]any)
		kind, _ := marker["kind"].(string)
		fake, _ := marker["fakeDns"].(bool)
		generated := map[string]any{
			"package_name": append([]any(nil), packages...),
		}
		if len(uids) > 0 {
			generated["user_id"] = append([]any(nil), uids...)
		}
		switch kind {
		case "direct":
			generated["server"] = "dns-direct"
		case "reject":
			generated["action"] = "reject"
		case "proxy":
			if fake {
				generated["server"] = "dns-fake"
				generated["inbound"] = []string{"tun-in"}
				generated["query_type"] = []string{"A", "AAAA"}
			} else {
				generated["server"] = "dns-remote"
			}
		}
		rules[index] = generated
	}
	return nil
}
