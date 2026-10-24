// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package module

import (
	"errors"
	"math"
	"strings"
)

const rootProtocolOutboundRouteMarker = "_wanbox_root_protocol_outbound_v78"

func rootProtocolOutboundMarker(rule map[string]any) (map[string]any, bool, error) {
	value, exists := rule[rootProtocolOutboundRouteMarker]
	if !exists {
		return nil, false, nil
	}
	marker, ok := value.(map[string]any)
	if !ok || len(rule) != 1 || len(marker) != 4 {
		return nil, true, errors.New("root_protocol_outbound_invalid")
	}
	for _, key := range []string{"index", "protocols", "outbound", "invert"} {
		if _, ok := marker[key]; !ok {
			return nil, true, errors.New("root_protocol_outbound_invalid")
		}
	}
	return marker, true, nil
}

func hasRootProtocolOutboundRouteMarker(config map[string]any) bool {
	rules, _ := nestedMap(config, "route")["rules"].([]any)
	for _, raw := range rules {
		if rule, ok := raw.(map[string]any); ok {
			if _, exists := rule[rootProtocolOutboundRouteMarker]; exists {
				return true
			}
		}
	}
	return false
}

func validateRootProtocolOutboundRoutes(config map[string]any, snapshot Snapshot) error {
	rules, ok := nestedMap(config, "route")["rules"].([]any)
	if !ok {
		return errors.New("root_protocol_outbound_invalid")
	}
	for index, raw := range rules {
		rule, ok := raw.(map[string]any)
		if !ok {
			continue
		}
		marker, exists, err := rootProtocolOutboundMarker(rule)
		if err != nil {
			return err
		}
		if !exists {
			continue
		}
		idx, idxOK := marker["index"].(float64)
		protocols, protocolsOK := marker["protocols"].([]any)
		outbound, outboundOK := marker["outbound"].(string)
		_, invertOK := marker["invert"].(bool)
		if !idxOK || math.IsNaN(idx) || math.IsInf(idx, 0) || math.Trunc(idx) != idx || idx != float64(index) ||
			!protocolsOK || len(protocols) == 0 || len(protocols) > 10 || !outboundOK || outbound == "" || len(outbound) > 256 ||
			strings.ContainsAny(outbound, "\\/\x00\r\n") || !invertOK || !validRootRouteNetworkOutboundTag(outbound) ||
			!hasRootOutboundTag(config, snapshot, outbound) || snapshot.RootInbound == nil || snapshot.RootInbound.Backend != "tun" {
			return errors.New("root_protocol_outbound_invalid")
		}
		seen := map[string]bool{}
		for _, value := range protocols {
			protocol, ok := value.(string)
			if !ok || !validRootRouteProtocolOutboundProtocol(protocol) || seen[protocol] {
				return errors.New("root_protocol_outbound_invalid")
			}
			seen[protocol] = true
		}
	}
	return nil
}

func materializeRootProtocolOutboundRoutes(config map[string]any) error {
	route := nestedMap(config, "route")
	rules, ok := route["rules"].([]any)
	if !ok {
		return errors.New("root_protocol_outbound_invalid")
	}
	for index, raw := range rules {
		rule, ok := raw.(map[string]any)
		if !ok {
			continue
		}
		marker, exists, err := rootProtocolOutboundMarker(rule)
		if err != nil {
			return err
		}
		if !exists {
			continue
		}
		protocols, protocolsOK := marker["protocols"].([]any)
		outbound, outboundOK := marker["outbound"].(string)
		invert, invertOK := marker["invert"].(bool)
		if !protocolsOK || !outboundOK || outbound == "" || !invertOK {
			return errors.New("root_protocol_outbound_invalid")
		}
		generated := map[string]any{"protocol": protocols, "outbound": outbound}
		if invert {
			generated["invert"] = true
		}
		rules[index] = generated
	}
	route["rules"] = rules
	config["route"] = route
	return nil
}
