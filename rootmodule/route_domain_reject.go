// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package module

import (
	"errors"
	"math"
	"strings"
)

const rootRouteDomainRejectMarker = "_wanbox_root_route_domain_reject_v40"
const maxRootRouteDomainRejectRuleID int64 = 9_007_199_254_740_991

type RootRouteDomainRejectRulesSpec struct {
	Rules []RootRouteDomainRejectRuleSpec `json:"rules"`
}

type RootRouteDomainRejectRuleSpec struct {
	RuleID     int64  `json:"ruleId"`
	DomainText string `json:"domainText"`
}

// parseRootRouteExactDomains mirrors the App's deliberately narrow full:-only grammar.
func parseRootRouteExactDomains(raw string) ([]string, error) {
	if len(raw) > 4096 {
		return nil, errors.New("root_route_domain_reject_invalid")
	}
	parts := strings.FieldsFunc(raw, func(r rune) bool { return r == ',' || r == '\n' })
	domains := make([]string, 0, len(parts))
	for _, part := range parts {
		token := strings.TrimSpace(part)
		if token == "" {
			continue
		}
		if !strings.HasPrefix(token, "full:") {
			return nil, errors.New("root_route_domain_reject_invalid")
		}
		domain := strings.TrimPrefix(token, "full:")
		if !validRootRouteExactDomain(domain) {
			return nil, errors.New("root_route_domain_reject_invalid")
		}
		domains = append(domains, strings.ToLower(domain))
		if len(domains) > 256 {
			return nil, errors.New("root_route_domain_reject_invalid")
		}
	}
	if len(domains) == 0 {
		return nil, errors.New("root_route_domain_reject_invalid")
	}
	return domains, nil
}

func validRootRouteExactDomain(domain string) bool {
	if len(domain) == 0 || len(domain) > 253 {
		return false
	}
	for _, label := range strings.Split(domain, ".") {
		if len(label) == 0 || len(label) > 63 {
			return false
		}
		for i, r := range label {
			alphaNumeric := r >= 'a' && r <= 'z' || r >= 'A' && r <= 'Z' || r >= '0' && r <= '9'
			if !alphaNumeric && r != '-' {
				return false
			}
			if (i == 0 || i == len(label)-1) && !alphaNumeric {
				return false
			}
		}
	}
	return true
}

func routeDomainRejectMarkerValue(rule map[string]any) (map[string]any, bool, error) {
	value, exists := rule[rootRouteDomainRejectMarker]
	if !exists {
		return nil, false, nil
	}
	marker, ok := value.(map[string]any)
	if len(rule) != 1 || !ok || len(marker) != 2 {
		return nil, true, errors.New("root_route_domain_reject_invalid")
	}
	if _, ok = marker["ruleId"]; !ok {
		return nil, true, errors.New("root_route_domain_reject_invalid")
	}
	if _, ok = marker["domainText"]; !ok {
		return nil, true, errors.New("root_route_domain_reject_invalid")
	}
	return marker, true, nil
}

func validateRootRouteDomainRejectRules(spec *RootRouteDomainRejectRulesSpec, config map[string]any) error {
	if spec == nil || len(spec.Rules) == 0 || len(spec.Rules) > 4096 {
		return errors.New("root_route_domain_reject_invalid")
	}
	seen := make(map[int64]struct{}, len(spec.Rules))
	for _, item := range spec.Rules {
		if item.RuleID < 1 || item.RuleID > maxRootRouteDomainRejectRuleID {
			return errors.New("root_route_domain_reject_invalid")
		}
		if _, ok := seen[item.RuleID]; ok {
			return errors.New("root_route_domain_reject_invalid")
		}
		seen[item.RuleID] = struct{}{}
		if _, err := parseRootRouteExactDomains(item.DomainText); err != nil {
			return errors.New("root_route_domain_reject_invalid")
		}
	}
	route, _ := config["route"].(map[string]any)
	rules, _ := route["rules"].([]any)
	found := 0
	for _, raw := range rules {
		rule, ok := raw.(map[string]any)
		if !ok {
			continue
		}
		marker, exists, err := routeDomainRejectMarkerValue(rule)
		if err != nil {
			return err
		}
		if !exists {
			continue
		}
		if found >= len(spec.Rules) {
			return errors.New("root_route_domain_reject_invalid")
		}
		id, ok := marker["ruleId"].(float64)
		if !ok || math.IsNaN(id) || math.IsInf(id, 0) || math.Trunc(id) != id || id < 1 || id > float64(maxRootRouteDomainRejectRuleID) {
			return errors.New("root_route_domain_reject_invalid")
		}
		text, ok := marker["domainText"].(string)
		if !ok {
			return errors.New("root_route_domain_reject_invalid")
		}
		if _, err := parseRootRouteExactDomains(text); err != nil {
			return errors.New("root_route_domain_reject_invalid")
		}
		want := spec.Rules[found]
		if int64(id) != want.RuleID || text != want.DomainText {
			return errors.New("root_route_domain_reject_invalid")
		}
		found++
	}
	if found != len(spec.Rules) {
		return errors.New("root_route_domain_reject_invalid")
	}
	return nil
}

func hasRootRouteDomainRejectMarker(config map[string]any) bool {
	route, _ := config["route"].(map[string]any)
	rules, _ := route["rules"].([]any)
	for _, raw := range rules {
		if rule, ok := raw.(map[string]any); ok {
			if _, exists := rule[rootRouteDomainRejectMarker]; exists {
				return true
			}
		}
	}
	return false
}

func materializeRootRouteDomainRejectRules(route map[string]any, spec *RootRouteDomainRejectRulesSpec) error {
	if err := validateRootRouteDomainRejectRules(spec, map[string]any{"route": route}); err != nil {
		return err
	}
	rules, _ := route["rules"].([]any)
	for index, raw := range rules {
		rule, ok := raw.(map[string]any)
		if !ok {
			continue
		}
		marker, exists, err := routeDomainRejectMarkerValue(rule)
		if err != nil {
			return err
		}
		if !exists {
			continue
		}
		domains, err := parseRootRouteExactDomains(marker["domainText"].(string))
		if err != nil {
			return err
		}
		rules[index] = map[string]any{"domain": domains, "action": "reject"}
	}
	route["rules"] = rules
	return nil
}
