package config

import (
	"strings"
	"testing"
)

func envFn(m map[string]string) func(string) string {
	return func(k string) string { return m[k] }
}

func TestLoad_RequiresDevIDAndKey(t *testing.T) {
	t.Parallel()
	_, err := loadFrom(envFn(map[string]string{}))
	if err == nil {
		t.Fatal("expected error when required env vars are missing")
	}
	msg := err.Error()
	for _, want := range []string{"OPENPTV_PTV_DEV_ID", "OPENPTV_PTV_KEY"} {
		if !strings.Contains(msg, want) {
			t.Errorf("error message %q missing %q", msg, want)
		}
	}
}

func TestLoad_AppliesDefaults(t *testing.T) {
	t.Parallel()
	cfg, err := loadFrom(envFn(map[string]string{
		"OPENPTV_PTV_DEV_ID": "1234",
		"OPENPTV_PTV_KEY":    "secret",
	}))
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if cfg.DevID != "1234" {
		t.Errorf("DevID = %q, want %q", cfg.DevID, "1234")
	}
	if cfg.Key != "secret" {
		t.Errorf("Key = %q, want %q", cfg.Key, "secret")
	}
	if cfg.BaseURL != DefaultBaseURL {
		t.Errorf("BaseURL = %q, want default %q", cfg.BaseURL, DefaultBaseURL)
	}
	if cfg.ListenAddr != DefaultListenAddr {
		t.Errorf("ListenAddr = %q, want default %q", cfg.ListenAddr, DefaultListenAddr)
	}
	if cfg.LogFormat != DefaultLogFormat {
		t.Errorf("LogFormat = %q, want default %q", cfg.LogFormat, DefaultLogFormat)
	}
}

func TestLoad_RejectsBadLogFormat(t *testing.T) {
	t.Parallel()
	_, err := loadFrom(envFn(map[string]string{
		"OPENPTV_PTV_DEV_ID": "1234",
		"OPENPTV_PTV_KEY":    "secret",
		"OPENPTV_LOG_FORMAT": "yaml",
	}))
	if err == nil {
		t.Fatal("expected error for invalid OPENPTV_LOG_FORMAT")
	}
	if !strings.Contains(err.Error(), "OPENPTV_LOG_FORMAT") {
		t.Errorf("error %q should mention OPENPTV_LOG_FORMAT", err.Error())
	}
}

func TestLoad_NeverEchoesSecretInError(t *testing.T) {
	t.Parallel()
	// Even when only the dev id is set, the resulting error must not have
	// leaked the (unset) key. This is a paranoia check: future refactors
	// must not change Load() to embed the value of OPENPTV_PTV_KEY in the
	// error message.
	const secret = "this-must-never-appear"
	_, err := loadFrom(envFn(map[string]string{
		"OPENPTV_PTV_KEY": secret,
	}))
	if err == nil {
		t.Fatal("expected error when DevID missing")
	}
	if strings.Contains(err.Error(), secret) {
		t.Fatalf("error message leaked secret: %q", err.Error())
	}
}
