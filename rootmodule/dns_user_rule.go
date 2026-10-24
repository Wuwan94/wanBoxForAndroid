// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package module

import (
	"errors"
	"math"
	"strings"
)

const rootExactDomainDNSRuleMarker = "_wanbox_root_exact_domain_dns_rule_v53"
const maxRootDNSRuleID int64 = 9_007_199_254_740_991

func exactDomainDNSMarkerValue(rule map[string]any) (map[string]any, bool, error) {
	value, exists := rule[rootExactDomainDNSRuleMarker]
	if !exists {
		return nil, false, nil
	}
	marker, ok := value.(map[string]any)
	if !ok || len(rule) != 1 || len(marker) != 4 {
		return nil, true, errors.New("root_dns_user_rule_invalid")
	}
	for _, key := range []string{"ruleId", "domainText", "kind", "fakeDns"} {
		if _, ok := marker[key]; !ok {
			return nil, true, errors.New("root_dns_user_rule_invalid")
		}
	}
	return marker, true, nil
}

func rootDNSRules(config map[string]any) ([]any, error) {
	dns, ok := config["dns"].(map[string]any)
	if !ok {
		return nil, errors.New("root_dns_user_rule_invalid")
	}
	rules, ok := dns["rules"].([]any)
	if !ok {
		return nil, errors.New("root_dns_user_rule_invalid")
	}
	return rules, nil
}

func hasRootExactDomainDNSRuleMarker(config map[string]any) bool {
	dns, _ := config["dns"].(map[string]any)
	rules, _ := dns["rules"].([]any)
	for _, raw := range rules {
		if rule, ok := raw.(map[string]any); ok {
			if _, exists := rule[rootExactDomainDNSRuleMarker]; exists {
				return true
			}
		}
	}
	return false
}

func validateRootExactDomainDNSRules(config map[string]any, snapshot Snapshot) error {
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
		marker, exists, err := exactDomainDNSMarkerValue(rule)
		if err != nil {
			return err
		}
		if !exists {
			continue
		}
		id, ok := marker["ruleId"].(float64)
		domainText, domainOK := marker["domainText"].(string)
		kind, kindOK := marker["kind"].(string)
		fake, fakeOK := marker["fakeDns"].(bool)
		if !ok || math.IsNaN(id) || math.IsInf(id, 0) || math.Trunc(id) != id || id < 1 || id > float64(maxRootDNSRuleID) || seen[int64(id)] ||
			!domainOK || !kindOK || !fakeOK || (kind != "direct" && kind != "reject" && kind != "proxy") || fake && kind != "proxy" {
			return errors.New("root_dns_user_rule_invalid")
		}
		if _, _, _, err := parseRootDNSDomainMatchers(domainText); err != nil {
			return errors.New("root_dns_user_rule_invalid")
		}
		if fake && (snapshot.RootInbound == nil || snapshot.RootInbound.Backend != "tun") {
			return errors.New("root_dns_user_rule_invalid")
		}
		if kind == "direct" && !hasDNSServer(config, "dns-direct") || kind == "proxy" && !fake && !hasDNSServer(config, "dns-remote") || fake && !hasDNSServer(config, "dns-fake") {
			return errors.New("root_dns_user_rule_invalid")
		}
		seen[int64(id)] = true
	}
	return nil
}

// parseRootDNSDomainMatchers mirrors makeSingBoxRule for the DNS-safe subset.
// Regex/geosite inputs stay on the App-generated path because their parity is not established here.
func parseRootDNSDomainMatchers(raw string) (domains, suffixes, keywords []string, err error) {
	if len(raw) > 4096 {
		return nil, nil, nil, errors.New("root_dns_user_rule_invalid")
	}
	parts := strings.FieldsFunc(raw, func(r rune) bool { return r == ',' || r == '\n' })
	for _, part := range parts {
		token := strings.TrimSpace(part)
		if token == "" {
			continue
		}
		target := &suffixes
		domain := token
		switch {
		case strings.HasPrefix(token, "full:"):
			target, domain = &domains, strings.TrimPrefix(token, "full:")
		case strings.HasPrefix(token, "domain:"):
			domain = strings.TrimPrefix(token, "domain:")
		case strings.HasPrefix(token, "keyword:"):
			target, domain = &keywords, strings.TrimPrefix(token, "keyword:")
		case strings.HasPrefix(token, "regexp:") || strings.HasPrefix(token, "geosite:") || strings.HasPrefix(token, "geosite-"):
			return nil, nil, nil, errors.New("root_dns_user_rule_invalid")
		}
		if !validRootRouteExactDomain(domain) {
			return nil, nil, nil, errors.New("root_dns_user_rule_invalid")
		}
		*target = append(*target, strings.ToLower(domain))
		if len(domains)+len(suffixes)+len(keywords) > 256 {
			return nil, nil, nil, errors.New("root_dns_user_rule_invalid")
		}
	}
	if len(domains)+len(suffixes)+len(keywords) == 0 {
		return nil, nil, nil, errors.New("root_dns_user_rule_invalid")
	}
	return domains, suffixes, keywords, nil
}

func hasDNSServer(config map[string]any, tag string) bool {
	dns, _ := config["dns"].(map[string]any)
	servers, _ := dns["servers"].([]any)
	for _, raw := range servers {
		server, ok := raw.(map[string]any)
		if ok && server["tag"] == tag {
			return true
		}
	}
	return false
}

// Root creates the narrowly-supported exact-domain DNS effect in the marker's
// original slot. The App keeps all unsupported/custom/ruleset DNS JSON intact.
func materializeRootExactDomainDNSRules(config map[string]any, snapshot Snapshot) error {
	if err := validateRootExactDomainDNSRules(config, snapshot); err != nil {
		return err
	}
	rules, _ := rootDNSRules(config)
	for index, raw := range rules {
		rule, ok := raw.(map[string]any)
		if !ok {
			continue
		}
		marker, exists, err := exactDomainDNSMarkerValue(rule)
		if err != nil {
			return err
		}
		if !exists {
			continue
		}
		domains, suffixes, keywords, err := parseRootDNSDomainMatchers(marker["domainText"].(string))
		if err != nil {
			return errors.New("root_dns_user_rule_invalid")
		}
		generated := map[string]any{}
		if len(domains) > 0 {
			generated["domain"] = domains
		}
		if len(suffixes) > 0 {
			generated["domain_suffix"] = suffixes
		}
		if len(keywords) > 0 {
			generated["domain_keyword"] = keywords
		}
		switch marker["kind"].(string) {
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
