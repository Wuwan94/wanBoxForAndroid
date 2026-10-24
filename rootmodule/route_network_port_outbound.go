// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package module

import (
	"errors"
	"math"
	"strings"
	"unicode/utf16"
)

const rootNetworkPortOutboundRouteMarker = "_wanbox_root_network_port_outbound_v57"
const maxRootNetworkPortOutboundRuleID int64 = 9_007_199_254_740_991

func parseRootNetworkPortOutboundPorts(raw string) ([]int, error) {
	if len(utf16.Encode([]rune(raw))) > 4096 {
		return nil, errors.New("root_network_port_outbound_invalid")
	}
	tokens := strings.FieldsFunc(raw, func(r rune) bool { return r == ',' || r == '\n' })
	ports := make([]int, 0, len(tokens))
	for _, token := range tokens {
		token = strings.TrimSpace(token)
		if token == "" {
			continue
		}
		if len(ports) >= 256 || len(token) > 5 {
			return nil, errors.New("root_network_port_outbound_invalid")
		}
		value := 0
		for _, char := range token {
			if char < '0' || char > '9' {
				return nil, errors.New("root_network_port_outbound_invalid")
			}
			value = value*10 + int(char-'0')
		}
		if value > 65535 {
			return nil, errors.New("root_network_port_outbound_invalid")
		}
		ports = append(ports, value)
	}
	if len(ports) == 0 {
		return nil, errors.New("root_network_port_outbound_invalid")
	}
	return ports, nil
}

func rootNetworkPortOutboundMarker(rule map[string]any) (map[string]any, bool, error) {
	value, exists := rule[rootNetworkPortOutboundRouteMarker]
	if !exists {
		return nil, false, nil
	}
	marker, ok := value.(map[string]any)
	if !ok || len(rule) != 1 || len(marker) != 5 {
		return nil, true, errors.New("root_network_port_outbound_invalid")
	}
	return marker, true, nil
}

func hasRootNetworkPortOutboundRouteMarker(config map[string]any) bool {
	rules, _ := nestedMap(config, "route")["rules"].([]any)
	for _, raw := range rules {
		if rule, ok := raw.(map[string]any); ok {
			if _, exists := rule[rootNetworkPortOutboundRouteMarker]; exists {
				return true
			}
		}
	}
	return false
}

func validateRootNetworkPortOutboundRoutes(config map[string]any, snapshot Snapshot) error {
	rules, ok := nestedMap(config, "route")["rules"].([]any)
	if !ok {
		return errors.New("root_network_port_outbound_invalid")
	}
	seen := map[int64]bool{}
	for index, raw := range rules {
		rule, ok := raw.(map[string]any)
		if !ok {
			continue
		}
		marker, exists, err := rootNetworkPortOutboundMarker(rule)
		if err != nil {
			return err
		}
		if !exists {
			continue
		}
		idx, idxOK := marker["index"].(float64)
		id, idOK := marker["ruleId"].(float64)
		networkText, networkOK := marker["networkText"].(string)
		portText, portOK := marker["portText"].(string)
		outbound, outboundOK := marker["outbound"].(string)
		if !idxOK || math.IsNaN(idx) || math.IsInf(idx, 0) || math.Trunc(idx) != idx || idx != float64(index) ||
			!idOK || math.IsNaN(id) || math.IsInf(id, 0) || math.Trunc(id) != id || id < 1 || id > float64(maxRootNetworkPortOutboundRuleID) || seen[int64(id)] ||
			!networkOK || !portOK || !outboundOK || strings.TrimSpace(outbound) != outbound || outbound == "" ||
			!validRootRouteNetworkOutboundTag(outbound) || !hasRootOutboundTag(config, snapshot, outbound) {
			return errors.New("root_network_port_outbound_invalid")
		}
		if _, err := parseRootRouteNetworkRejectNetworks(networkText); err != nil {
			return errors.New("root_network_port_outbound_invalid")
		}
		if _, err := parseRootNetworkPortOutboundPorts(portText); err != nil {
			return errors.New("root_network_port_outbound_invalid")
		}
		seen[int64(id)] = true
	}
	return nil
}

func materializeRootNetworkPortOutboundRoutes(config map[string]any) error {
	route := nestedMap(config, "route")
	rules, ok := route["rules"].([]any)
	if !ok {
		return errors.New("root_network_port_outbound_invalid")
	}
	for index, raw := range rules {
		rule, ok := raw.(map[string]any)
		if !ok {
			continue
		}
		marker, exists, err := rootNetworkPortOutboundMarker(rule)
		if err != nil {
			return err
		}
		if !exists {
			continue
		}
		idx, idxOK := marker["index"].(float64)
		networkText, networkOK := marker["networkText"].(string)
		portText, portOK := marker["portText"].(string)
		outbound, outboundOK := marker["outbound"].(string)
		if !idxOK || idx != float64(index) || !networkOK || !portOK || !outboundOK {
			return errors.New("root_network_port_outbound_invalid")
		}
		networks, err := parseRootRouteNetworkRejectNetworks(networkText)
		if err != nil {
			return errors.New("root_network_port_outbound_invalid")
		}
		ports, err := parseRootNetworkPortOutboundPorts(portText)
		if err != nil {
			return errors.New("root_network_port_outbound_invalid")
		}
		rules[index] = map[string]any{"network": networks, "port": ports, "outbound": outbound}
	}
	route["rules"] = rules
	config["route"] = route
	return nil
}
