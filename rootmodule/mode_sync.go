// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package module

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"os"
	"strings"
	"time"
)

const maxClashAPIResponseBytes = 4096

type clashModeTarget struct {
	Skip   bool
	Mode   string
	Secret string
}

func (r *Runtime) readClashModeTarget(revision string) (clashModeTarget, error) {
	if revision == "" || !revisionName.MatchString(revision) {
		return clashModeTarget{}, errors.New("invalid_revision")
	}
	path := r.path("generations", revision, "run", "config.json")
	f, err := os.Open(path)
	if err != nil {
		return clashModeTarget{}, errors.New("config_open_failed")
	}
	defer f.Close()

	limited := io.LimitReader(f, MaxConfigBytes+1)
	data, err := io.ReadAll(limited)
	if err != nil || len(data) > MaxConfigBytes {
		return clashModeTarget{}, errors.New("config_too_large")
	}

	var raw struct {
		Experimental *struct {
			ClashAPI *struct {
				ExternalController *string         `json:"external_controller"`
				Secret             *string         `json:"secret"`
				DefaultMode        json.RawMessage `json:"default_mode"`
			} `json:"clash_api"`
		} `json:"experimental"`
	}
	if err := json.Unmarshal(data, &raw); err != nil {
		return clashModeTarget{}, errors.New("config_parse_failed")
	}
	if raw.Experimental == nil || raw.Experimental.ClashAPI == nil {
		return clashModeTarget{Skip: true}, nil
	}
	api := raw.Experimental.ClashAPI
	if len(api.DefaultMode) == 0 {
		return clashModeTarget{Skip: true}, nil
	}

	rawDefaultMode := string(bytes.TrimSpace(api.DefaultMode))
	if rawDefaultMode == "null" {
		return clashModeTarget{}, errors.New("invalid_default_mode")
	}

	var modeStr string
	if err := json.Unmarshal(api.DefaultMode, &modeStr); err != nil {
		return clashModeTarget{}, errors.New("invalid_default_mode")
	}
	if modeStr == "" {
		return clashModeTarget{Skip: true}, nil
	}
	if strings.Trim(modeStr, " \t\r\n") != modeStr || strings.TrimSpace(modeStr) == "" {
		return clashModeTarget{}, errors.New("invalid_default_mode")
	}

	normalized := strings.ToLower(modeStr)
	switch normalized {
	case "rule", "global", "direct":
	default:
		return clashModeTarget{}, errors.New("invalid_default_mode")
	}

	if api.ExternalController == nil || *api.ExternalController != "127.0.0.1:9090" {
		return clashModeTarget{}, errors.New("invalid_controller")
	}
	if api.Secret == nil || *api.Secret == "" {
		return clashModeTarget{}, errors.New("secret_missing")
	}

	return clashModeTarget{
		Skip:   false,
		Mode:   normalized,
		Secret: *api.Secret,
	}, nil
}

func (r *Runtime) checkGenerationMatch(revision string, core ProcessIdentity) error {
	if !SameProcess(core) {
		return errors.New("core_identity_mismatch")
	}
	cur, err := r.CurrentRevision()
	if err != nil || cur != revision {
		return errors.New("revision_mismatch")
	}
	return nil
}

func drainBoundedResponse(body io.ReadCloser, maxBytes int64) ([]byte, error) {
	defer body.Close()
	limited := io.LimitReader(body, maxBytes+1)
	data, err := io.ReadAll(limited)
	if err != nil {
		return nil, errors.New("response_read_failed")
	}
	if int64(len(data)) > maxBytes {
		return nil, errors.New("response_overflow")
	}
	return data, nil
}

// SetRuntimeMode sets only the runtime mode through the Clash API and reads it
// back for confirmation. It never touches the configured default mode: leaving
// a `direct` network restores the default via AlignClashMode. An API failure
// MUST NOT fall back to a config reload (Fail-Closed: keep the current mode
// and report the error). B6（N3）: the `direct` action of unified network rules.
func (r *Runtime) SetRuntimeMode(ctx context.Context, revision string, core ProcessIdentity, mode string) error {
	switch mode {
	case "rule", "global", "direct":
	default:
		return errors.New("invalid_runtime_mode")
	}
	target, err := r.readClashModeTarget(revision)
	if err != nil {
		return err
	}
	if target.Skip {
		return errors.New("mode_api_unavailable")
	}
	guard := func() error {
		return r.checkGenerationMatch(revision, core)
	}
	return r.patchAndConfirmMode(ctx, revision, loopbackClient, "http://127.0.0.1:9090/configs", guard, mode, target.Secret)
}

// patchAndConfirmMode PATCHes the Clash API mode and GETs it back; the
// returned mode MUST equal the requested mode, otherwise the switch is
// reported as failed and no reload is attempted.
func (r *Runtime) patchAndConfirmMode(
	ctx context.Context,
	revision string,
	client *http.Client,
	endpoint string,
	guard func() error,
	mode, secret string,
) error {
	if err := guard(); err != nil {
		return err
	}

	reqCtx, cancel := context.WithTimeout(ctx, 5*time.Second)
	defer cancel()

	patchPayload, _ := json.Marshal(map[string]string{"mode": mode})
	patchReq, err := http.NewRequestWithContext(reqCtx, http.MethodPatch, endpoint, bytes.NewReader(patchPayload))
	if err != nil {
		return errors.New("patch_request_failed")
	}
	patchReq.Header.Set("Content-Type", "application/json")
	patchReq.Header.Set("Authorization", "Bearer "+secret)

	patchResp, err := client.Do(patchReq)
	if err != nil {
		return errors.New("patch_network_failed")
	}
	if _, err := drainBoundedResponse(patchResp.Body, maxClashAPIResponseBytes); err != nil {
		return err
	}
	if patchResp.StatusCode < 200 || patchResp.StatusCode >= 300 {
		return errors.New("patch_status_failed")
	}

	if err := guard(); err != nil {
		return err
	}

	getReq, err := http.NewRequestWithContext(reqCtx, http.MethodGet, endpoint, nil)
	if err != nil {
		return errors.New("get_request_failed")
	}
	getReq.Header.Set("Authorization", "Bearer "+secret)

	getResp, err := client.Do(getReq)
	if err != nil {
		return errors.New("get_network_failed")
	}
	respData, err := drainBoundedResponse(getResp.Body, maxClashAPIResponseBytes)
	if err != nil {
		return err
	}
	if getResp.StatusCode != http.StatusOK {
		return errors.New("get_status_failed")
	}

	var res struct {
		Mode string `json:"mode"`
	}
	if err := json.Unmarshal(respData, &res); err != nil {
		return errors.New("get_payload_malformed")
	}

	if err := guard(); err != nil {
		return err
	}

	if !strings.EqualFold(res.Mode, mode) {
		return errors.New("mode_mismatch")
	}
	return nil
}

func (r *Runtime) AlignClashMode(ctx context.Context, revision string, core ProcessIdentity) error {
	guard := func() error {
		return r.checkGenerationMatch(revision, core)
	}
	return r.alignClashModeInternal(ctx, revision, loopbackClient, "http://127.0.0.1:9090/configs", guard)
}

func (r *Runtime) alignClashModeInternal(
	ctx context.Context,
	revision string,
	client *http.Client,
	endpoint string,
	guard func() error,
) error {
	target, err := r.readClashModeTarget(revision)
	if err != nil {
		return err
	}
	if target.Skip {
		return nil
	}

	return r.patchAndConfirmMode(ctx, revision, client, endpoint, guard, target.Mode, target.Secret)
}
