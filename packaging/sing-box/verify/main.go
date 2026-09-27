// Command verify checks the REALITY ClientHello a patched core sends, the
// way an Xray 26.9 server reads it, and what a real server needs of it:
//
//   - an X25519MLKEM768 key share, once, ahead of the first X25519 one, which
//     is the order XTLS/REALITY 8cdf7bf9 reads the shares in;
//   - a session id that, opened with the server's private key, names client
//     version 26.3.27;
//   - every key share's group among the supported groups, and X25519MLKEM768
//     offered only with its share (RFC 8446 4.2.8; XTLS/Xray-core#6714);
//   - ALPN h2 and http/1.1, as a browser offers them.
//
// It stands in for the server: it accepts one connection, reads the
// ClientHello, prints what it found, and exits 0 when all of it holds, 1 when
// any of it does not, 2 when there was no hello to judge.
//
//	verify -listen 127.0.0.1:18443 -private-key <base64url, from sing-box generate reality-keypair>
package main

import (
	"crypto/aes"
	"crypto/cipher"
	"crypto/ecdh"
	"crypto/hkdf"
	"crypto/sha256"
	"encoding/base64"
	"encoding/binary"
	"errors"
	"flag"
	"fmt"
	"io"
	"net"
	"os"
	"slices"
	"strings"
	"time"
)

const (
	groupX25519              = 0x001d
	groupX25519MLKEM         = 0x11ec
	mlkemKeySize             = 1184
	extensionSupportedGroups = 0x000a
	extensionALPN            = 0x0010
	extensionKeyShare        = 0x0033
)

var (
	wantVersion = [3]byte{26, 3, 27}
	wantALPN    = []string{"h2", "http/1.1"}
)

func main() {
	listen := flag.String("listen", "127.0.0.1:18443", "address to accept the core's connection on")
	privateKey := flag.String("private-key", "", "the REALITY server private key, base64url")
	timeout := flag.Duration("timeout", 60*time.Second, "how long to wait for the hello")
	flag.Parse()

	key, err := base64.RawURLEncoding.DecodeString(*privateKey)
	if err != nil || len(key) != 32 {
		fail(2, "private key: need 32 bytes of base64url")
	}
	listener, err := net.Listen("tcp", *listen)
	if err != nil {
		fail(2, "listen: %v", err)
	}
	fmt.Printf("waiting for a ClientHello on %s\n", listener.Addr())
	_ = listener.(*net.TCPListener).SetDeadline(time.Now().Add(*timeout))
	conn, err := listener.Accept()
	if err != nil {
		fail(2, "accept: %v", err)
	}
	_ = conn.SetDeadline(time.Now().Add(10 * time.Second))
	raw, err := readClientHello(conn)
	if err != nil {
		fail(2, "read: %v", err)
	}
	hello, err := parse(raw)
	if err != nil {
		fail(2, "parse: %v", err)
	}
	fmt.Printf("key shares: %s\n", hello.shareNames())
	fmt.Printf("supported groups: %s\n", groupList(hello.groups))
	fmt.Printf("ALPN: %s\n", strings.Join(hello.alpn, ", "))

	ok := true
	if err := realityAccepts(hello); err != nil {
		fmt.Printf("FAIL: %v; XTLS/REALITY 8cdf7bf9 rejects such a hello\n", err)
		ok = false
	}
	if err := groupsMatchShares(hello); err != nil {
		fmt.Printf("FAIL: %v\n", err)
		ok = false
	}
	if !slices.Equal(hello.alpn, wantALPN) {
		fmt.Printf("FAIL: ALPN %q, want %q as a browser offers it\n", hello.alpn, wantALPN)
		ok = false
	}
	version, err := openSessionID(key, hello)
	if err != nil {
		fmt.Printf("FAIL: session id: %v\n", err)
		ok = false
	} else if version != wantVersion {
		fmt.Printf("FAIL: client version %d.%d.%d, want %d.%d.%d\n", version[0], version[1],
			version[2], wantVersion[0], wantVersion[1], wantVersion[2])
		ok = false
	} else {
		fmt.Printf("client version %d.%d.%d\n", version[0], version[1], version[2])
	}
	if !ok {
		os.Exit(1)
	}
	fmt.Println("OK")
}

type keyShare struct {
	group uint16
	data  []byte
}

type clientHello struct {
	raw       []byte // the handshake message, header included, as REALITY hashes it
	random    []byte
	sessionID []byte
	keyShares []keyShare // in the order the hello sends them
	groups    []uint16   // supported_groups, in order
	alpn      []string
}

// share returns the data of the first key share of group, or nil.
func (h clientHello) share(group uint16) []byte {
	for _, s := range h.keyShares {
		if s.group == group {
			return s.data
		}
	}
	return nil
}

func (h clientHello) shareNames() string {
	names := make([]string, 0, len(h.keyShares))
	for _, s := range h.keyShares {
		if s.group == groupX25519MLKEM {
			names = append(names, fmt.Sprintf("%s (%d B)", groupName(s.group), len(s.data)))
		} else {
			names = append(names, groupName(s.group))
		}
	}
	return strings.Join(names, ", ")
}

func groupList(groups []uint16) string {
	names := make([]string, 0, len(groups))
	for _, group := range groups {
		names = append(names, groupName(group))
	}
	return strings.Join(names, ", ")
}

func groupName(group uint16) string {
	switch {
	case group == groupX25519:
		return "X25519"
	case group == groupX25519MLKEM:
		return "X25519MLKEM768"
	case group == 0x0017:
		return "P-256"
	case group == 0x0018:
		return "P-384"
	case group == 0x0019:
		return "P-521"
	case isGREASE(group):
		return "GREASE"
	default:
		return fmt.Sprintf("0x%04x", group)
	}
}

// isGREASE tells a GREASE value (RFC 8701): 0x?A?A with both bytes the same.
func isGREASE(value uint16) bool {
	return value&0x0f0f == 0x0a0a && value>>8 == value&0xff
}

// realityAccepts reads the key shares as XTLS/REALITY 8cdf7bf9 does: it walks
// them in order, takes an X25519MLKEM768 share once, and stops at the first
// X25519 one. A hello whose hybrid share does not come before that is turned
// away.
func realityAccepts(hello clientHello) error {
	hybrid := false
	for _, s := range hello.keyShares {
		if s.group == groupX25519MLKEM && len(s.data) == mlkemKeySize+32 {
			if hybrid {
				return errors.New("two X25519MLKEM768 key shares")
			}
			hybrid = true
			continue
		}
		if s.group == groupX25519 && len(s.data) == 32 {
			break
		}
	}
	if !hybrid {
		return errors.New("no X25519MLKEM768 key share ahead of X25519")
	}
	return nil
}

// groupsMatchShares checks what RFC 8446 4.2.8 asks, and what utls's
// randomized hello did not always hold: every key share's group is among the
// supported groups, and X25519MLKEM768 is offered only with its share.
// Offered without it, a server asks for the share in a HelloRetryRequest,
// and utls gives the handshake up (XTLS/Xray-core#6714).
func groupsMatchShares(hello clientHello) error {
	for _, s := range hello.keyShares {
		if !isGREASE(s.group) && !slices.Contains(hello.groups, s.group) {
			return fmt.Errorf("a %s key share whose group is not among the supported ones",
				groupName(s.group))
		}
	}
	if slices.Contains(hello.groups, groupX25519MLKEM) && hello.share(groupX25519MLKEM) == nil {
		return errors.New("X25519MLKEM768 among the supported groups without its key share")
	}
	return nil
}

// readClientHello reads TLS records until one handshake message is whole.
func readClientHello(conn net.Conn) ([]byte, error) {
	var message []byte
	for {
		header := make([]byte, 5)
		if _, err := io.ReadFull(conn, header); err != nil {
			return nil, err
		}
		if header[0] != 0x16 {
			return nil, fmt.Errorf("record type %d, not a handshake", header[0])
		}
		body := make([]byte, binary.BigEndian.Uint16(header[3:]))
		if _, err := io.ReadFull(conn, body); err != nil {
			return nil, err
		}
		message = append(message, body...)
		if len(message) >= 4 {
			need := 4 + (int(message[1])<<16 | int(message[2])<<8 | int(message[3]))
			if len(message) >= need {
				return message[:need], nil
			}
		}
	}
}

func parse(raw []byte) (clientHello, error) {
	hello := clientHello{raw: raw}
	if len(raw) < 4 || raw[0] != 0x01 {
		return hello, errors.New("not a ClientHello")
	}
	b := raw[4:]
	take := func(n int) ([]byte, error) {
		if len(b) < n {
			return nil, errors.New("truncated")
		}
		out := b[:n]
		b = b[n:]
		return out, nil
	}
	if _, err := take(2); err != nil { // legacy version
		return hello, err
	}
	var err error
	if hello.random, err = take(32); err != nil {
		return hello, err
	}
	idLength, err := take(1)
	if err != nil {
		return hello, err
	}
	if hello.sessionID, err = take(int(idLength[0])); err != nil {
		return hello, err
	}
	suites, err := take(2)
	if err != nil {
		return hello, err
	}
	if _, err = take(int(binary.BigEndian.Uint16(suites))); err != nil {
		return hello, err
	}
	methods, err := take(1)
	if err != nil {
		return hello, err
	}
	if _, err = take(int(methods[0])); err != nil {
		return hello, err
	}
	extensionsLength, err := take(2)
	if err != nil {
		return hello, err
	}
	extensions, err := take(int(binary.BigEndian.Uint16(extensionsLength)))
	if err != nil {
		return hello, err
	}
	for len(extensions) >= 4 {
		kind := binary.BigEndian.Uint16(extensions)
		length := int(binary.BigEndian.Uint16(extensions[2:]))
		if len(extensions) < 4+length {
			return hello, errors.New("truncated extension")
		}
		data := extensions[4 : 4+length]
		extensions = extensions[4+length:]
		switch kind {
		case extensionKeyShare:
			err = hello.readKeyShares(data)
		case extensionSupportedGroups:
			err = hello.readGroups(data)
		case extensionALPN:
			err = hello.readALPN(data)
		}
		if err != nil {
			return hello, err
		}
	}
	return hello, nil
}

// readKeyShares reads the key_share extension's shares, in order.
func (h *clientHello) readKeyShares(data []byte) error {
	if len(data) < 2 {
		return errors.New("truncated key share list")
	}
	shares := data[2:]
	for len(shares) >= 4 {
		group := binary.BigEndian.Uint16(shares)
		size := int(binary.BigEndian.Uint16(shares[2:]))
		if len(shares) < 4+size {
			return errors.New("truncated key share")
		}
		h.keyShares = append(h.keyShares, keyShare{group: group, data: shares[4 : 4+size]})
		shares = shares[4+size:]
	}
	return nil
}

// readGroups reads the supported_groups extension, in order.
func (h *clientHello) readGroups(data []byte) error {
	if len(data) < 2 {
		return errors.New("truncated supported groups")
	}
	list := data[2:]
	for len(list) >= 2 {
		h.groups = append(h.groups, binary.BigEndian.Uint16(list))
		list = list[2:]
	}
	return nil
}

// readALPN reads the protocol names the ALPN extension offers.
func (h *clientHello) readALPN(data []byte) error {
	if len(data) < 2 {
		return errors.New("truncated ALPN")
	}
	list := data[2:]
	for len(list) > 0 {
		size := int(list[0])
		if len(list) < 1+size {
			return errors.New("truncated ALPN protocol")
		}
		h.alpn = append(h.alpn, string(list[1:1+size]))
		list = list[1+size:]
	}
	return nil
}

// openSessionID derives the auth key the way XTLS/REALITY 8cdf7bf9 does (the
// plain X25519 share first, else the X25519 half of the hybrid one) and opens
// the session id sealed with it; the first three bytes are the version.
func openSessionID(privateKey []byte, hello clientHello) ([3]byte, error) {
	var version [3]byte
	peer := hello.share(groupX25519)
	if peer == nil {
		if hybrid := hello.share(groupX25519MLKEM); len(hybrid) == mlkemKeySize+32 {
			peer = hybrid[mlkemKeySize:]
		}
	}
	if len(peer) != 32 {
		return version, errors.New("no X25519 key to derive the auth key from")
	}
	private, err := ecdh.X25519().NewPrivateKey(privateKey)
	if err != nil {
		return version, err
	}
	public, err := ecdh.X25519().NewPublicKey(peer)
	if err != nil {
		return version, err
	}
	shared, err := private.ECDH(public)
	if err != nil {
		return version, err
	}
	authKey, err := hkdf.Key(sha256.New, shared, hello.random[:20], "REALITY", 32)
	if err != nil {
		return version, err
	}
	block, err := aes.NewCipher(authKey)
	if err != nil {
		return version, err
	}
	aead, err := cipher.NewGCM(block)
	if err != nil {
		return version, err
	}
	// The client sealed with the hello as it was before the session id went
	// in: zeros in its place.
	aad := append([]byte(nil), hello.raw...)
	copy(aad[39:39+len(hello.sessionID)], make([]byte, len(hello.sessionID)))
	plain, err := aead.Open(nil, hello.random[20:], hello.sessionID, aad)
	if err != nil {
		return version, fmt.Errorf("does not open with this key: %v", err)
	}
	copy(version[:], plain)
	return version, nil
}

func fail(code int, format string, args ...any) {
	fmt.Fprintf(os.Stderr, format+"\n", args...)
	os.Exit(code)
}
