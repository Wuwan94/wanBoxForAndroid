// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package module

import (
	"errors"
	"math"
	"net/url"
	"strings"
)

const rootRemoteRuleSetMarker = "_wanbox_root_remote_rule_set_v46"

func validRootRemoteRuleSetURL(raw string) bool {
	if raw == "" || len(raw) > 4096 || strings.TrimSpace(raw) != raw || strings.ContainsAny(raw, "\\\x00\r\n") {
		return false
	}
	parsed, err := url.ParseRequestURI(raw)
	return err == nil && (parsed.Scheme == "http" || parsed.Scheme == "https") && parsed.Host != ""
}

func validateRootRemoteRuleSets(config map[string]any) error {
	route, _ := config["route"].(map[string]any)
	entries, _ := route["rule_set"].([]any)
	found := 0
	tags := map[string]bool{}
	for index, raw := range entries {
		item, ok := raw.(map[string]any)
		if !ok {
			continue
		}
		value, exists := item[rootRemoteRuleSetMarker]
		if !exists {
			continue
		}
		if len(item) != 1 {
			return errors.New("root_remote_rule_set_invalid")
		}
		marker, ok := value.(map[string]any)
		if !ok || len(marker) != 4 {
			return errors.New("root_remote_rule_set_invalid")
		}
		markerIndex, ok := marker["index"].(float64)
		tag, tagOK := marker["tag"].(string)
		rawURL, urlOK := marker["url"].(string)
		interval, intervalOK := marker["updateInterval"].(string)
		if !ok || math.IsNaN(markerIndex) || math.IsInf(markerIndex, 0) || math.Trunc(markerIndex) != markerIndex || markerIndex != float64(index) ||
			!tagOK || !validRootRouteNetworkOutboundTag(tag) || tags[tag] || !urlOK || !validRootRemoteRuleSetURL(rawURL) || !intervalOK || len(interval) > 64 || strings.ContainsAny(interval, "\\\x00\r\n") {
			return errors.New("root_remote_rule_set_invalid")
		}
		tags[tag] = true
		found++
	}
	return nil
}

func hasRootRemoteRuleSetMarker(config map[string]any) bool {
	route, _ := config["route"].(map[string]any)
	entries, _ := route["rule_set"].([]any)
	for _, raw := range entries {
		if item, ok := raw.(map[string]any); ok {
			if _, exists := item[rootRemoteRuleSetMarker]; exists {
				return true
			}
		}
	}
	return false
}

func materializeRootRemoteRuleSets(route map[string]any) error {
	entries, _ := route["rule_set"].([]any)
	found := false
	for index, raw := range entries {
		item, ok := raw.(map[string]any)
		if !ok {
			continue
		}
		value, exists := item[rootRemoteRuleSetMarker]
		if !exists {
			continue
		}
		marker, ok := value.(map[string]any)
		if !ok || len(item) != 1 || len(marker) != 4 || marker["index"] != float64(index) {
			return errors.New("root_remote_rule_set_invalid")
		}
		tag, tagOK := marker["tag"].(string)
		rawURL, urlOK := marker["url"].(string)
		interval, intervalOK := marker["updateInterval"].(string)
		if !tagOK || !validRootRouteNetworkOutboundTag(tag) || !urlOK || !validRootRemoteRuleSetURL(rawURL) || !intervalOK || len(interval) > 64 || strings.ContainsAny(interval, "\\\x00\r\n") {
			return errors.New("root_remote_rule_set_invalid")
		}
		generated := map[string]any{"type": "remote", "tag": tag, "format": "binary", "url": rawURL, "update_interval": interval}
		entries[index] = generated
		found = true
	}
	if found {
		route["rule_set"] = entries
	}
	return nil
}
