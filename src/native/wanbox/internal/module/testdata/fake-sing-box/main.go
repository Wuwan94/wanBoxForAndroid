package main

import (
	"os"
)

func main() {
	if os.Getenv("WANBOX_FAKE_SING_BOX_FAIL") == "1" {
		os.Exit(1)
	}
}
