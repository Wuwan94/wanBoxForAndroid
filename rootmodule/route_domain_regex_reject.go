// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package module

import (
	"bytes"
	"encoding/json"
	"errors"
	"math"
)

const rootRouteDomainRegexRejectMarker = "_wanbox_root_route_domain_regex_reject_v43"

type RootRouteDomainRegexRejectRulesSpec struct {
	Rules []RootRouteDomainRegexRejectRuleSpec `json:"rules"`
}

type RootRouteDomainRegexRejectRuleSpec struct {
	Index int             `json:"index"`
	Rule  json.RawMessage `json:"rule"`
}

// validateRootRouteDomainRegexRejectRules validates only the projection envelope.
// The rule payload is kept whole and passed to the pinned sing-box core unchanged,
// so regex syntax and every matcher/action option retain core-defined behavior.
func validateRootRouteDomainRegexRejectRules(spec *RootRouteDomainRegexRejectRulesSpec, config map[string]any) error {
	if spec == nil || len(spec.Rules) == 0 || len(spec.Rules) > 4096 {
		return errors.New("root_route_domain_regex_reject_invalid")
	}
	route, _ := config["route"].(map[string]any)
	rules, _ := route["rules"].([]any)
	found := 0
	for index, raw := range rules {
		rule, ok := raw.(map[string]any)
		if !ok {
			continue
		}
		value, exists := rule[rootRouteDomainRegexRejectMarker]
		if !exists {
			continue
		}
		if len(rule) != 1 || found >= len(spec.Rules) {
			return errors.New("root_route_domain_regex_reject_invalid")
		}
		marker, ok := value.(map[string]any)
		if !ok || len(marker) != 1 {
			return errors.New("root_route_domain_regex_reject_invalid")
		}
		markerIndex, ok := marker["index"].(float64)
		if !ok || math.IsNaN(markerIndex) || math.IsInf(markerIndex, 0) || math.Trunc(markerIndex) != markerIndex || markerIndex < 0 || markerIndex > 1<<20 {
			return errors.New("root_route_domain_regex_reject_invalid")
		}
		item := spec.Rules[found]
		if item.Index != index || int(markerIndex) != index || !validRawDomainRegexRejectRule(item.Rule) {
			return errors.New("root_route_domain_regex_reject_invalid")
		}
		found++
	}
	if found != len(spec.Rules) {
		return errors.New("root_route_domain_regex_reject_invalid")
	}
	return nil
}

func validRawDomainRegexRejectRule(raw json.RawMessage) bool {
	if len(raw) == 0 || len(raw) > MaxConfigBytes || !json.Valid(raw) {
		return false
	}
	var rule map[string]json.RawMessage
	if json.Unmarshal(raw, &rule) != nil || rule == nil {
		return false
	}
	var action string
	if json.Unmarshal(rule["action"], &action) != nil || action != "reject" {
		return false
	}
	var patterns []json.RawMessage
	if json.Unmarshal(rule["domain_regex"], &patterns) != nil || len(patterns) == 0 {
		return false
	}
	for _, pattern := range patterns {
		var value string
		if json.Unmarshal(pattern, &value) != nil {
			return false
		}
	}
	return true
}

func hasRootRouteDomainRegexRejectMarker(config map[string]any) bool {
	route, _ := config["route"].(map[string]any)
	rules, _ := route["rules"].([]any)
	for _, raw := range rules {
		if rule, ok := raw.(map[string]any); ok {
			if _, exists := rule[rootRouteDomainRegexRejectMarker]; exists {
				return true
			}
		}
	}
	return false
}

// materializeRootRouteDomainRegexRejectRules replaces projection markers in-place.
// Other Root rules may already have been inserted, so only marker sequence (not the
// current array coordinate) is checked here; the original source index was validated
// before any materialization and the in-place marker preserves ordering through inserts.
func materializeRootRouteDomainRegexRejectRules(route map[string]any, spec *RootRouteDomainRegexRejectRulesSpec) error {
	if spec == nil || len(spec.Rules) == 0 {
		return errors.New("root_route_domain_regex_reject_invalid")
	}
	rules, _ := route["rules"].([]any)
	found := 0
	for index, raw := range rules {
		rule, ok := raw.(map[string]any)
		if !ok {
			continue
		}
		value, exists := rule[rootRouteDomainRegexRejectMarker]
		if !exists {
			continue
		}
		marker, ok := value.(map[string]any)
		if len(rule) != 1 || !ok || len(marker) != 1 || found >= len(spec.Rules) {
			return errors.New("root_route_domain_regex_reject_invalid")
		}
		markerIndex, ok := marker["index"].(float64)
		item := spec.Rules[found]
		if !ok || math.Trunc(markerIndex) != markerIndex || markerIndex != float64(item.Index) || !validRawDomainRegexRejectRule(item.Rule) {
			return errors.New("root_route_domain_regex_reject_invalid")
		}
		rules[index] = json.RawMessage(bytes.Clone(item.Rule))
		found++
	}
	if found != len(spec.Rules) {
		return errors.New("root_route_domain_regex_reject_invalid")
	}
	route["rules"] = rules
	return nil
}
