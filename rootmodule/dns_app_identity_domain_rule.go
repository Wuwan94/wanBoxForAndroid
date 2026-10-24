// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package module

import (
	"errors"
	"math"
)

const rootAppIdentityDomainDNSRuleMarker = "_wanbox_root_app_identity_domain_dns_rule_v80"

func appIdentityDomainDNSMarker(rule map[string]any) (map[string]any, bool, error) {
	value, exists := rule[rootAppIdentityDomainDNSRuleMarker]
	if !exists {
		return nil, false, nil
	}
	marker, ok := value.(map[string]any)
	if !ok || len(rule) != 1 || len(marker) != 6 {
		return nil, true, errors.New("root_app_identity_domain_dns_invalid")
	}
	for _, key := range []string{"ruleId", "packages", "uids", "domainText", "kind", "fakeDns"} {
		if _, ok := marker[key]; !ok {
			return nil, true, errors.New("root_app_identity_domain_dns_invalid")
		}
	}
	return marker, true, nil
}

func hasRootAppIdentityDomainDNSRuleMarker(config map[string]any) bool {
	dns, _ := config["dns"].(map[string]any)
	rules, _ := dns["rules"].([]any)
	for _, raw := range rules {
		if rule, ok := raw.(map[string]any); ok {
			if _, exists := rule[rootAppIdentityDomainDNSRuleMarker]; exists {
				return true
			}
		}
	}
	return false
}

func validateRootAppIdentityDomainDNSRules(config map[string]any, snapshot Snapshot) error {
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
		marker, exists, err := appIdentityDomainDNSMarker(rule)
		if err != nil {
			return err
		}
		if !exists {
			continue
		}
		id, idOK := marker["ruleId"].(float64)
		packages, packagesOK := marker["packages"].([]any)
		uids, uidsOK := marker["uids"].([]any)
		domainText, domainOK := marker["domainText"].(string)
		kind, kindOK := marker["kind"].(string)
		fake, fakeOK := marker["fakeDns"].(bool)
		if !idOK || math.IsNaN(id) || math.IsInf(id, 0) || math.Trunc(id) != id || id < 1 || id > float64(maxRootDNSRuleID) || seen[int64(id)] ||
			!packagesOK || len(packages) == 0 || len(packages) > 256 || !uidsOK || len(uids) > 4096 || !domainOK || !kindOK || !fakeOK ||
			(kind != "direct" && kind != "reject" && kind != "proxy") || fake && kind != "proxy" {
			return errors.New("root_app_identity_domain_dns_invalid")
		}
		if _, _, _, err := parseRootDNSDomainMatchers(domainText); err != nil {
			return errors.New("root_app_identity_domain_dns_invalid")
		}
		seen[int64(id)] = true
		seenPackages := map[string]bool{}
		for _, rawPackage := range packages {
			packageName, ok := rawPackage.(string)
			if !ok || !validRootRouteAppUIDPackage(packageName) || seenPackages[packageName] {
				return errors.New("root_app_identity_domain_dns_invalid")
			}
			seenPackages[packageName] = true
		}
		for _, rawUID := range uids {
			uid, ok := rawUID.(float64)
			if !ok || math.IsNaN(uid) || math.IsInf(uid, 0) || math.Trunc(uid) != uid || uid < 0 || uid > math.MaxInt32 {
				return errors.New("root_app_identity_domain_dns_invalid")
			}
		}
		if fake && (snapshot.RootInbound == nil || snapshot.RootInbound.Backend != "tun" || !hasDNSServer(config, "dns-fake")) ||
			kind == "direct" && !hasDNSServer(config, "dns-direct") && (snapshot.RootDNSDirectServer == nil || snapshot.RootDNSDirectServer.Tag != "dns-direct") || kind == "proxy" && !fake && !hasDNSServer(config, "dns-remote") {
			return errors.New("root_app_identity_domain_dns_invalid")
		}
	}
	return nil
}

// The App's package AND supported-domain DNS effect is reconstructed in place;
// the corresponding route rule and any unsupported DNS rules remain untouched.
func materializeRootAppIdentityDomainDNSRules(config map[string]any, snapshot Snapshot) error {
	if err := validateRootAppIdentityDomainDNSRules(config, snapshot); err != nil {
		return err
	}
	rules, _ := rootDNSRules(config)
	for index, raw := range rules {
		rule, ok := raw.(map[string]any)
		if !ok {
			continue
		}
		marker, exists, err := appIdentityDomainDNSMarker(rule)
		if err != nil {
			return err
		}
		if !exists {
			continue
		}
		packages, _ := marker["packages"].([]any)
		uids, _ := marker["uids"].([]any)
		domains, suffixes, keywords, err := parseRootDNSDomainMatchers(marker["domainText"].(string))
		if err != nil {
			return errors.New("root_app_identity_domain_dns_invalid")
		}
		generated := map[string]any{"package_name": append([]any(nil), packages...)}
		if len(uids) > 0 {
			generated["user_id"] = append([]any(nil), uids...)
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
		kind := marker["kind"].(string)
		switch kind {
		case "direct":
			generated["server"] = "dns-direct"
		case "reject":
			generated["action"] = "reject"
		case "proxy":
			if marker["fakeDns"].(bool) {
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
