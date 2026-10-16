package agent

import (
	"crypto/ed25519"
	"crypto/rand"
	"os"
	"path/filepath"
	"testing"
	"time"
)

// A relative state directory used to break -exec: the app command was started
// with cmd.Dir set to the slot directory, so the relative app path was resolved
// from inside that directory and the start failed with "no such file or directory".
func TestExecWithRelativeStateDir(t *testing.T) {
	tmp := t.TempDir()
	wd, err := os.Getwd()
	if err != nil {
		t.Fatal(err)
	}
	if err := os.Chdir(tmp); err != nil {
		t.Fatal(err)
	}
	defer os.Chdir(wd)

	slot := filepath.Join("dev-1", "slots", "a")
	if err := os.MkdirAll(slot, 0o755); err != nil {
		t.Fatal(err)
	}
	marker := filepath.Join(tmp, "started")
	script := "#!/bin/sh\ntouch " + marker + "\nsleep 5\n"
	if err := os.WriteFile(filepath.Join(slot, "app"), []byte(script), 0o755); err != nil {
		t.Fatal(err)
	}

	// an existing key file means New does not try to enroll with a server
	_, priv, _ := ed25519.GenerateKey(rand.Reader)
	if err := os.WriteFile(filepath.Join("dev-1", "key"), priv, 0o600); err != nil {
		t.Fatal(err)
	}
	a, err := New(Config{DeviceID: "dev-1", StateDir: "dev-1", Exec: true})
	if err != nil {
		t.Fatal(err)
	}
	a.startApp("a")
	defer a.stopApp()
	if a.lastErr != "" {
		t.Fatalf("app did not start: %s", a.lastErr)
	}
	deadline := time.Now().Add(5 * time.Second)
	for {
		if _, err := os.Stat(marker); err == nil {
			return
		}
		if time.Now().After(deadline) {
			t.Fatal("app never ran")
		}
		time.Sleep(20 * time.Millisecond)
	}
}
