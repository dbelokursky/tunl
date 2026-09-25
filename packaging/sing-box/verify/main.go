// Command verify checks the REALITY ClientHello a patched core sends, the
// way an Xray 26.9 server reads it: the hello carries an X25519MLKEM768 key
// share, and its session id, opened with the server's private key, names
// client version 26.3.27.
//
// It stands in for the server: it accepts one connection, reads the
// ClientHello, prints what it found, and exits 0 when both hold, 1 when
// either does not, 2 when there was no hello to judge.
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
	"time"
)

const (
	groupX25519       = 0x001d
	groupX25519MLKEM  = 0x11ec
	mlkemKeySize      = 1184
	extensionKeyShare = 0x0033
)

var wantVersion = [3]byte{26, 3, 27}

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
	fmt.Printf("key shares: %s\n", hello.groupNames())

	ok := true
	if _, hybrid := hello.shares[groupX25519MLKEM]; !hybrid {
		fmt.Println("FAIL: no X25519MLKEM768 key share; XTLS/REALITY 8cdf7bf9 rejects such a hello")
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

type clientHello struct {
	raw       []byte // the handshake message, header included, as REALITY hashes it
	random    []byte
	sessionID []byte
	shares    map[uint16][]byte
	order     []uint16
}

func (h clientHello) groupNames() string {
	names := ""
	for i, group := range h.order {
		if i > 0 {
			names += ", "
		}
		switch group {
		case groupX25519:
			names += "X25519"
		case groupX25519MLKEM:
			names += fmt.Sprintf("X25519MLKEM768 (%d B)", len(h.shares[group]))
		default:
			names += fmt.Sprintf("0x%04x", group)
		}
	}
	return names
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
	hello := clientHello{raw: raw, shares: map[uint16][]byte{}}
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
		if kind != extensionKeyShare || len(data) < 2 {
			continue
		}
		shares := data[2:]
		for len(shares) >= 4 {
			group := binary.BigEndian.Uint16(shares)
			size := int(binary.BigEndian.Uint16(shares[2:]))
			if len(shares) < 4+size {
				return hello, errors.New("truncated key share")
			}
			hello.shares[group] = shares[4 : 4+size]
			hello.order = append(hello.order, group)
			shares = shares[4+size:]
		}
	}
	return hello, nil
}

// openSessionID derives the auth key the way XTLS/REALITY 8cdf7bf9 does (the
// plain X25519 share first, else the X25519 half of the hybrid one) and opens
// the session id sealed with it; the first three bytes are the version.
func openSessionID(privateKey []byte, hello clientHello) ([3]byte, error) {
	var version [3]byte
	peer := hello.shares[groupX25519]
	if peer == nil {
		if hybrid := hello.shares[groupX25519MLKEM]; len(hybrid) == mlkemKeySize+32 {
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
