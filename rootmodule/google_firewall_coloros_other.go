//go:build !android || !arm64

// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package module

func repairColorOSGoogleFirewall(map[uint32]bool, func() error) (int, error) {
	return 0, nil
}
