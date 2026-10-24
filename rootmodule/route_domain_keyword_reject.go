// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package module

import (
	"errors"
	"math"
	"strings"
)

const rootRouteDomainKeywordRejectMarker = "_wanbox_root_route_domain_keyword_reject_v42"
const maxRootRouteDomainKeywordRejectRuleID int64 = 9_007_199_254_740_991

type RootRouteDomainKeywordRejectRulesSpec struct {
	Rules []RootRouteDomainKeywordRejectRuleSpec `json:"rules"`
}

type RootRouteDomainKeywordRejectRuleSpec struct {
	RuleID     int64  `json:"ruleId"`
	DomainText string `json:"domainText"`
}

// parseRootRouteKeywordDomains mirrors the App's conservative explicit keyword:-only grammar.
func parseRootRouteKeywordDomains(raw string) ([]string, error) {
	if len(raw) > 4096 {
		return nil, errors.New("root_route_domain_keyword_reject_invalid")
	}
	parts := strings.FieldsFunc(raw, func(r rune) bool { return r == ',' || r == '\n' })
	keywords := make([]string, 0, len(parts))
	for _, part := range parts {
		token := strings.TrimSpace(part)
		if token == "" {
			continue
		}
		if !strings.HasPrefix(token, "keyword:") {
			return nil, errors.New("root_route_domain_keyword_reject_invalid")
		}
		keyword := strings.TrimPrefix(token, "keyword:")
		if len(keyword) == 0 || len(keyword) > 253 {
			return nil, errors.New("root_route_domain_keyword_reject_invalid")
		}
		for _, r := range keyword {
			if !(r >= 'a' && r <= 'z' || r >= 'A' && r <= 'Z' || r >= '0' && r <= '9' || r == '-' || r == '.' || r == '_') {
				return nil, errors.New("root_route_domain_keyword_reject_invalid")
			}
		}
		keywords = append(keywords, strings.ToLower(keyword))
		if len(keywords) > 256 {
			return nil, errors.New("root_route_domain_keyword_reject_invalid")
		}
	}
	if len(keywords) == 0 {
		return nil, errors.New("root_route_domain_keyword_reject_invalid")
	}
	return keywords, nil
}

func routeDomainKeywordRejectMarkerValue(rule map[string]any) (map[string]any, bool, error) {
	value, exists := rule[rootRouteDomainKeywordRejectMarker]
	if !exists {
		return nil, false, nil
	}
	marker, ok := value.(map[string]any)
	if len(rule) != 1 || !ok || len(marker) != 2 {
		return nil, true, errors.New("root_route_domain_keyword_reject_invalid")
	}
	if _, ok = marker["ruleId"]; !ok {
		return nil, true, errors.New("root_route_domain_keyword_reject_invalid")
	}
	if _, ok = marker["domainText"]; !ok {
		return nil, true, errors.New("root_route_domain_keyword_reject_invalid")
	}
	return marker, true, nil
}

func validateRootRouteDomainKeywordRejectRules(spec *RootRouteDomainKeywordRejectRulesSpec, config map[string]any) error {
	if spec == nil || len(spec.Rules) == 0 || len(spec.Rules) > 4096 {
		return errors.New("root_route_domain_keyword_reject_invalid")
	}
	seen := make(map[int64]struct{}, len(spec.Rules))
	for _, item := range spec.Rules {
		if item.RuleID < 1 || item.RuleID > maxRootRouteDomainKeywordRejectRuleID {
			return errors.New("root_route_domain_keyword_reject_invalid")
		}
		if _, ok := seen[item.RuleID]; ok {
			return errors.New("root_route_domain_keyword_reject_invalid")
		}
		seen[item.RuleID] = struct{}{}
		if _, err := parseRootRouteKeywordDomains(item.DomainText); err != nil {
			return errors.New("root_route_domain_keyword_reject_invalid")
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
		marker, exists, err := routeDomainKeywordRejectMarkerValue(rule)
		if err != nil {
			return err
		}
		if !exists {
			continue
		}
		if found >= len(spec.Rules) {
			return errors.New("root_route_domain_keyword_reject_invalid")
		}
		id, ok := marker["ruleId"].(float64)
		if !ok || math.IsNaN(id) || math.IsInf(id, 0) || math.Trunc(id) != id || id < 1 || id > float64(maxRootRouteDomainKeywordRejectRuleID) {
			return errors.New("root_route_domain_keyword_reject_invalid")
		}
		text, ok := marker["domainText"].(string)
		if !ok {
			return errors.New("root_route_domain_keyword_reject_invalid")
		}
		if _, err := parseRootRouteKeywordDomains(text); err != nil {
			return errors.New("root_route_domain_keyword_reject_invalid")
		}
		want := spec.Rules[found]
		if int64(id) != want.RuleID || text != want.DomainText {
			return errors.New("root_route_domain_keyword_reject_invalid")
		}
		found++
	}
	if found != len(spec.Rules) {
		return errors.New("root_route_domain_keyword_reject_invalid")
	}
	return nil
}

func hasRootRouteDomainKeywordRejectMarker(config map[string]any) bool {
	route, _ := config["route"].(map[string]any)
	rules, _ := route["rules"].([]any)
	for _, raw := range rules {
		if rule, ok := raw.(map[string]any); ok {
			if _, exists := rule[rootRouteDomainKeywordRejectMarker]; exists {
				return true
			}
		}
	}
	return false
}

func materializeRootRouteDomainKeywordRejectRules(route map[string]any, spec *RootRouteDomainKeywordRejectRulesSpec) error {
	if err := validateRootRouteDomainKeywordRejectRules(spec, map[string]any{"route": route}); err != nil {
		return err
	}
	rules, _ := route["rules"].([]any)
	for index, raw := range rules {
		rule, ok := raw.(map[string]any)
		if !ok {
			continue
		}
		marker, exists, err := routeDomainKeywordRejectMarkerValue(rule)
		if err != nil {
			return err
		}
		if !exists {
			continue
		}
		domains, err := parseRootRouteKeywordDomains(marker["domainText"].(string))
		if err != nil {
			return err
		}
		rules[index] = map[string]any{"domain_keyword": domains, "action": "reject"}
	}
	route["rules"] = rules
	return nil
}
