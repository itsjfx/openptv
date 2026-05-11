// Package ptv encapsulates everything that touches the PTV signing key.
//
// Signer is the only type in the codebase that knows the key. Keep it that way.
package ptv

import (
	"crypto/hmac"
	"crypto/sha1"
	"encoding/hex"
	"errors"
	"fmt"
	"strings"
)

// Signer signs PTV API paths with HMAC-SHA1 per the published scheme:
//
//	url   = /v3/<path>?<query>&devid=<DEV_ID>
//	sig   = uppercase(hex(hmac_sha1(KEY, url)))
//	final = /v3/<path>?<query>&devid=<DEV_ID>&signature=<sig>
//
// The "devid must be appended before signing" rule is enforced here so that
// callers cannot accidentally sign a path without it.
type Signer struct {
	devID string
	key   []byte
}

// NewSigner validates inputs and returns a ready-to-use Signer.
func NewSigner(devID, key string) (*Signer, error) {
	if strings.TrimSpace(devID) == "" {
		return nil, errors.New("ptv: devID is required")
	}
	if key == "" {
		return nil, errors.New("ptv: key is required")
	}
	return &Signer{devID: devID, key: []byte(key)}, nil
}

// Sign accepts a path-with-optional-query like "/v3/route_types" or
// "/v3/stops/1071/route_types/0?max_results=3", appends devid, computes the
// HMAC-SHA1 signature, and returns the fully signed path (no host).
//
// The returned value is intended to be concatenated onto a base URL.
func (s *Signer) Sign(rawPath string) (string, error) {
	if s == nil {
		return "", errors.New("ptv: nil signer")
	}
	if !strings.HasPrefix(rawPath, "/") {
		return "", fmt.Errorf("ptv: path must start with /, got %q", rawPath)
	}

	// Append devid using the appropriate separator.
	sep := "?"
	if strings.Contains(rawPath, "?") {
		sep = "&"
	}
	withDevID := rawPath + sep + "devid=" + s.devID

	mac := hmac.New(sha1.New, s.key)
	// hash.Hash.Write never returns an error per the io.Writer contract for
	// the stdlib hash implementations; ignore by design.
	_, _ = mac.Write([]byte(withDevID))
	sig := strings.ToUpper(hex.EncodeToString(mac.Sum(nil)))

	return withDevID + "&signature=" + sig, nil
}

// DevID exposes the configured developer id. The key is intentionally not
// exposed; nothing outside this package should ever need it.
func (s *Signer) DevID() string { return s.devID }
