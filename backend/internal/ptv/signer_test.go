package ptv

import (
	"strings"
	"testing"
)

// TestSigner_FixtureMatchesPTVDocsExample pins the HMAC-SHA1 output against
// a known input. If this test ever changes value, callers will see broken
// upstream calls before we ship — that's the point.
//
// Inputs:
//
//	key   = "9c132d31-6a30-4cac-8d8b-8a1970834799"   (public PTV docs sample)
//	path  = "/v3/route_types"
//	devid = "3000176"                                 (PTV docs sample)
//
// Expected signature was computed once with:
//
//	printf '%s' '/v3/route_types?devid=3000176' \
//	  | openssl dgst -sha1 -hmac '9c132d31-6a30-4cac-8d8b-8a1970834799'
//
// and uppercased.
func TestSigner_FixtureMatchesPTVDocsExample(t *testing.T) {
	t.Parallel()
	const (
		devID    = "3000176"
		key      = "9c132d31-6a30-4cac-8d8b-8a1970834799"
		path     = "/v3/route_types"
		expected = "/v3/route_types?devid=3000176&signature=EBD12B055DFEBB7CC0F9FB2B6E3AA0FE3CFD87B6"
	)
	s, err := NewSigner(devID, key)
	if err != nil {
		t.Fatalf("NewSigner: %v", err)
	}
	got, err := s.Sign(path)
	if err != nil {
		t.Fatalf("Sign: %v", err)
	}
	if got != expected {
		t.Errorf("Sign(%q)\n  got:  %s\n  want: %s", path, got, expected)
	}
}

func TestSigner_PreservesExistingQuery(t *testing.T) {
	t.Parallel()
	s, err := NewSigner("DEV", "KEY")
	if err != nil {
		t.Fatalf("NewSigner: %v", err)
	}
	got, err := s.Sign("/v3/stops/1071/route_types/0?max_results=3")
	if err != nil {
		t.Fatalf("Sign: %v", err)
	}
	// devid is appended with & because a query is already present.
	if !strings.Contains(got, "max_results=3&devid=DEV&signature=") {
		t.Errorf("expected appended devid & signature, got %q", got)
	}
}

func TestSigner_RejectsRelativePath(t *testing.T) {
	t.Parallel()
	s, err := NewSigner("DEV", "KEY")
	if err != nil {
		t.Fatalf("NewSigner: %v", err)
	}
	if _, err := s.Sign("v3/route_types"); err == nil {
		t.Fatal("expected error for path without leading slash")
	}
}

func TestNewSigner_ValidatesInputs(t *testing.T) {
	t.Parallel()
	if _, err := NewSigner("", "k"); err == nil {
		t.Error("expected error for empty devID")
	}
	if _, err := NewSigner("dev", ""); err == nil {
		t.Error("expected error for empty key")
	}
}
