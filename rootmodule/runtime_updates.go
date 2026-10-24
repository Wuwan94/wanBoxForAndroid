// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package module

import (
	"bytes"
	"encoding/json"
	"reflect"
	"time"
)

// identicalSnapshotContent is deliberately byte-exact. A changed resource,
// plugin, policy or profile must still take the validated transaction path.
func identicalSnapshotContent(a, b Snapshot) bool {
	if a.SchemaVersion != b.SchemaVersion || a.PerformancePriority != b.PerformancePriority ||
		a.AutoStart != b.AutoStart || a.ProfileID != b.ProfileID || a.ProfileName != b.ProfileName ||
		!bytes.Equal(a.Config, b.Config) || !reflect.DeepEqual(a.RootInbound, b.RootInbound) || !reflect.DeepEqual(a.RootProxyGroup, b.RootProxyGroup) || !reflect.DeepEqual(a.RootOutbounds, b.RootOutbounds) || !reflect.DeepEqual(a.RootRoutePolicy, b.RootRoutePolicy) || !reflect.DeepEqual(a.RootDNSHosts, b.RootDNSHosts) || !reflect.DeepEqual(a.RootDNSPolicy, b.RootDNSPolicy) || !reflect.DeepEqual(a.RootDNSLocalServer, b.RootDNSLocalServer) || !reflect.DeepEqual(a.RootDNSDirectServer, b.RootDNSDirectServer) || !reflect.DeepEqual(a.RootRouteFinal, b.RootRouteFinal) || !reflect.DeepEqual(a.RootRouteLoopback, b.RootRouteLoopback) || !reflect.DeepEqual(a.RootRouteMulticast, b.RootRouteMulticast) || !reflect.DeepEqual(a.RootDNSOutboundAnyRule, b.RootDNSOutboundAnyRule) || !reflect.DeepEqual(a.RootDNSIPv6QueryRule, b.RootDNSIPv6QueryRule) || !reflect.DeepEqual(a.RootRouteDNSPort53Rule, b.RootRouteDNSPort53Rule) || !reflect.DeepEqual(a.RootRouteDNSProtocolRule, b.RootRouteDNSProtocolRule) || !reflect.DeepEqual(a.RootRouteBypassLAN, b.RootRouteBypassLAN) || !reflect.DeepEqual(a.RootRouteIPVersionRule, b.RootRouteIPVersionRule) || !reflect.DeepEqual(a.RootRoutePrivateBypass, b.RootRoutePrivateBypass) || !reflect.DeepEqual(a.RootRouteDNSHijackPort53Rule, b.RootRouteDNSHijackPort53Rule) || !reflect.DeepEqual(a.RootRouteDNSProtocolHijackRule, b.RootRouteDNSProtocolHijackRule) || !reflect.DeepEqual(a.RootRouteSniffRule, b.RootRouteSniffRule) || !reflect.DeepEqual(a.RootRouteProbeRule, b.RootRouteProbeRule) || !reflect.DeepEqual(a.RootRouteResolveRule, b.RootRouteResolveRule) || !reflect.DeepEqual(a.RootRouteModeRules, b.RootRouteModeRules) || !reflect.DeepEqual(a.RootRouteDNSTargetRules, b.RootRouteDNSTargetRules) || !reflect.DeepEqual(a.RootDNSResolvers, b.RootDNSResolvers) || !reflect.DeepEqual(a.RootRoutePortRejectRules, b.RootRoutePortRejectRules) || !reflect.DeepEqual(a.RootRouteNetworkRejectRules, b.RootRouteNetworkRejectRules) || !reflect.DeepEqual(a.RootRouteNetworkPortRejectRules, b.RootRouteNetworkPortRejectRules) || !reflect.DeepEqual(a.RootRouteSourcePortRejectRules, b.RootRouteSourcePortRejectRules) || !reflect.DeepEqual(a.RootRouteProtocolRejectRules, b.RootRouteProtocolRejectRules) || !reflect.DeepEqual(a.RootRouteSourceIPRejectRules, b.RootRouteSourceIPRejectRules) || !reflect.DeepEqual(a.RootRouteIPRejectRules, b.RootRouteIPRejectRules) || !reflect.DeepEqual(a.RootRouteSourceDestinationIPRejectRules, b.RootRouteSourceDestinationIPRejectRules) || !reflect.DeepEqual(a.RootRouteSourceDestinationPortRejectRules, b.RootRouteSourceDestinationPortRejectRules) || !reflect.DeepEqual(a.RootRouteProtocolPortRejectRules, b.RootRouteProtocolPortRejectRules) || !reflect.DeepEqual(a.RootRouteDomainRejectRules, b.RootRouteDomainRejectRules) || !reflect.DeepEqual(a.RootRouteDomainSuffixRejectRules, b.RootRouteDomainSuffixRejectRules) || !reflect.DeepEqual(a.RootRouteDomainKeywordRejectRules, b.RootRouteDomainKeywordRejectRules) || !reflect.DeepEqual(a.RootRouteDomainRegexRejectRules, b.RootRouteDomainRegexRejectRules) || !reflect.DeepEqual(a.RootRouteNetworkOutboundRules, b.RootRouteNetworkOutboundRules) || !reflect.DeepEqual(a.RootRouteAppUIDRules, b.RootRouteAppUIDRules) || !reflect.DeepEqual(a.RootAppUIDDomainOutboundRoutes, b.RootAppUIDDomainOutboundRoutes) || !reflect.DeepEqual(a.RootAppUIDRuleSetOutboundRoutes, b.RootAppUIDRuleSetOutboundRoutes) || !reflect.DeepEqual(a.RootAppUIDDomainRuleSetOutboundRoutes, b.RootAppUIDDomainRuleSetOutboundRoutes) || len(a.Files) != len(b.Files) || len(a.Plugins) != len(b.Plugins) {
		return false
	}
	for name, data := range a.Files {
		other, exists := b.Files[name]
		if !exists || !bytes.Equal(data, other) {
			return false
		}
	}
	for i, plugin := range a.Plugins {
		if plugin != b.Plugins[i] {
			return false
		}
	}
	return true
}

// replaceStats suppresses identical periodic samples, not state transitions.
// The caller owns the state lock and the freshly marshalled sample.
func replaceStats(state *State, sample json.RawMessage) bool {
	if bytes.Equal(state.Stats, sample) {
		return false
	}
	state.Stats = sample
	return true
}

// startupProbe owns only startup timers. Nil channels disable both select cases
// after readiness; process exit and cancellation keep their original owners.
type startupProbe struct {
	ticker   *time.Ticker
	timer    *time.Timer
	tick     <-chan time.Time
	deadline <-chan time.Time
}

func newStartupProbe(interval, timeout time.Duration) *startupProbe {
	ticker, timer := time.NewTicker(interval), time.NewTimer(timeout)
	return &startupProbe{ticker: ticker, timer: timer, tick: ticker.C, deadline: timer.C}
}

func (p *startupProbe) stop() {
	p.ticker.Stop()
	p.timer.Stop()
	p.tick, p.deadline = nil, nil
}
