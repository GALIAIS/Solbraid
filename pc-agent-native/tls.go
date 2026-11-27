package main

import (
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/sha256"
	"crypto/tls"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/hex"
	"encoding/pem"
	"fmt"
	"math/big"
	"net"
	"os"
	"path/filepath"
	"strings"
	"time"
)

// ensureSelfSignedTLS generates or reuses an ECDSA P-256 certificate in cfgDir/tls/.
func ensureSelfSignedTLS(cfgDir string) (certPath, keyPath, fingerprint string, err error) {
	tlsDir := filepath.Join(cfgDir, "tls")
	if err = os.MkdirAll(tlsDir, 0o700); err != nil {
		return "", "", "", err
	}
	certPath = filepath.Join(tlsDir, "server.crt")
	keyPath = filepath.Join(tlsDir, "server.key")

	if _, errC := os.Stat(certPath); errC == nil {
		if _, errK := os.Stat(keyPath); errK == nil {
			if fp, errFP := certSHA256Fingerprint(certPath); errFP == nil {
				return certPath, keyPath, fp, nil
			}
		}
	}

	priv, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		return "", "", "", err
	}
	serial, err := rand.Int(rand.Reader, new(big.Int).Lsh(big.NewInt(1), 128))
	if err != nil {
		return "", "", "", err
	}
	host, _ := os.Hostname()
	ips := []net.IP{net.ParseIP("127.0.0.1"), net.ParseIP("::1")}
	for _, n := range collectNetworks() {
		if ip := net.ParseIP(n.Address); ip != nil {
			ips = append(ips, ip)
		}
	}
	dnsNames := []string{"localhost"}
	if host != "" {
		dnsNames = append(dnsNames, host, host+".local")
	}

	tmpl := &x509.Certificate{
		SerialNumber: serial,
		Subject: pkix.Name{
			CommonName:   "Solbraid Desktop Agent (" + host + ")",
			Organization: []string{"Solbraid"},
		},
		NotBefore:             time.Now().Add(-10 * time.Minute),
		NotAfter:              time.Now().Add(3650 * 24 * time.Hour),
		KeyUsage:              x509.KeyUsageDigitalSignature | x509.KeyUsageKeyEncipherment,
		ExtKeyUsage:           []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth},
		BasicConstraintsValid: true,
		DNSNames:              dnsNames,
		IPAddresses:           ips,
	}
	der, err := x509.CreateCertificate(rand.Reader, tmpl, tmpl, &priv.PublicKey, priv)
	if err != nil {
		return "", "", "", err
	}
	certPEM := pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: der})
	keyDER, err := x509.MarshalECPrivateKey(priv)
	if err != nil {
		return "", "", "", err
	}
	keyPEM := pem.EncodeToMemory(&pem.Block{Type: "EC PRIVATE KEY", Bytes: keyDER})

	if err = os.WriteFile(certPath, certPEM, 0o600); err != nil {
		return "", "", "", err
	}
	if err = os.WriteFile(keyPath, keyPEM, 0o600); err != nil {
		return "", "", "", err
	}
	sum := sha256.Sum256(der)
	return certPath, keyPath, strings.ToUpper(hex.EncodeToString(sum[:])), nil
}

func certSHA256Fingerprint(certPath string) (string, error) {
	raw, err := os.ReadFile(certPath)
	if err != nil {
		return "", err
	}
	block, _ := pem.Decode(raw)
	if block == nil {
		return "", fmt.Errorf("invalid PEM certificate: %s", certPath)
	}
	sum := sha256.Sum256(block.Bytes)
	return strings.ToUpper(hex.EncodeToString(sum[:])), nil
}

func loadTLSConfig(certPath, keyPath string) (*tls.Config, string, error) {
	cert, err := tls.LoadX509KeyPair(certPath, keyPath)
	if err != nil {
		return nil, "", err
	}
	fp, err := certSHA256Fingerprint(certPath)
	if err != nil {
		return nil, "", err
	}
	return &tls.Config{
		MinVersion:   tls.VersionTLS12,
		Certificates: []tls.Certificate{cert},
	}, fp, nil
}
