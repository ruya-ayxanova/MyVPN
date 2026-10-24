// MyVPN sunucusu (v2): X25519 el sıkışması + PSK + ChaCha20-Poly1305,
// çoklu istemci, replay koruması, otomatik IP atama, NAT/forwarding kurulumu.
//
//	go mod tidy && go build -o myvpn .
//	./myvpn -genkey                              # PSK üret
//	sudo MYVPN_PSK=<anahtar> ./myvpn -listen :51820
//
// UDP 51820 portunu güvenlik duvarında açmayı unutmayın.
package main

import (
	"crypto/cipher"
	"crypto/ecdh"
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"encoding/binary"
	"flag"
	"fmt"
	"io"
	"log"
	"net"
	"net/url"
	"os"
	"os/exec"
	"os/signal"
	"strings"
	"sync"
	"sync/atomic"
	"syscall"
	"time"

	"golang.org/x/crypto/chacha20poly1305"
	"golang.org/x/crypto/hkdf"
	"golang.org/x/sys/unix"
)

const (
	tInit       = 1
	tResp       = 2
	tData       = 3
	mtu         = 1380
	initLen     = 61 // type(1) clientID(8) sid(4) ephPub(32) mac(16)
	subnet      = "10.8.0.0/24"
	serverIP    = "10.8.0.1/24"
	maxSessions = 4096
)

// ---- Replay penceresi (64 paket) ----
type window struct{ max, bm uint64 }

func (w *window) ok(c uint64) bool {
	if c == 0 {
		return false
	}
	if c > w.max {
		return true
	}
	d := w.max - c
	if d >= 64 {
		return false
	}
	return (w.bm>>d)&1 == 0
}

func (w *window) mark(c uint64) {
	if c > w.max {
		s := c - w.max
		if s >= 64 {
			w.bm = 1
		} else {
			w.bm = w.bm<<s | 1
		}
		w.max = c
	} else {
		w.bm |= 1 << (w.max - c)
	}
}

func nonce(c uint64) []byte {
	n := make([]byte, 12)
	binary.BigEndian.PutUint64(n[4:], c)
	return n
}

type session struct {
	sid     uint32
	ip      [4]byte
	rx, tx  cipher.AEAD
	txCtr   atomic.Uint64
	created time.Time
	mu      sync.Mutex
	win     window
	addr    *net.UDPAddr
	last    time.Time
	active  bool
}

type server struct {
	conn     *net.UDPConn
	tun      *os.File
	psk      []byte
	mu       sync.RWMutex
	sessions map[uint32]*session
	byIP     map[[4]byte]*session
	clientIP map[[8]byte][4]byte
	nextHost int
}

func (s *server) allocIP(cid [8]byte) ([4]byte, bool) {
	if ip, ok := s.clientIP[cid]; ok {
		return ip, true
	}
	if s.nextHost > 254 {
		return [4]byte{}, false
	}
	ip := [4]byte{10, 8, 0, byte(s.nextHost)}
	s.nextHost++
	s.clientIP[cid] = ip
	return ip, true
}

func (s *server) handleInit(b []byte, from *net.UDPAddr) {
	if len(b) != initLen {
		return
	}
	m := hmac.New(sha256.New, s.psk)
	m.Write(b[:45])
	if !hmac.Equal(m.Sum(nil)[:16], b[45:61]) {
		return // PSK yanlış veya sahte paket
	}
	var cid [8]byte
	copy(cid[:], b[1:9])
	sid := binary.BigEndian.Uint32(b[9:13])
	cpubB := b[13:45]
	cpub, err := ecdh.X25519().NewPublicKey(cpubB)
	if err != nil {
		return
	}
	epriv, err := ecdh.X25519().GenerateKey(rand.Reader)
	if err != nil {
		return
	}
	shared, err := epriv.ECDH(cpub)
	if err != nil {
		return
	}
	spubB := epriv.PublicKey().Bytes()

	info := []byte("myvpn-v1")
	info = append(info, cpubB...)
	info = append(info, spubB...)
	info = append(info, cid[:]...)
	info = binary.BigEndian.AppendUint32(info, sid)
	okm := make([]byte, 64)
	if _, err := io.ReadFull(hkdf.New(sha256.New, shared, s.psk, info), okm); err != nil {
		return
	}
	rx, _ := chacha20poly1305.New(okm[:32])  // istemci -> sunucu
	tx, _ := chacha20poly1305.New(okm[32:])  // sunucu -> istemci

	s.mu.Lock()
	if _, dup := s.sessions[sid]; dup || len(s.sessions) >= maxSessions {
		s.mu.Unlock()
		return
	}
	ip, ok := s.allocIP(cid)
	if !ok {
		s.mu.Unlock()
		log.Printf("IP havuzu dolu")
		return
	}
	sess := &session{sid: sid, ip: ip, rx: rx, tx: tx, created: time.Now(), addr: from, last: time.Now()}
	s.sessions[sid] = sess
	s.mu.Unlock()

	hdr := []byte{tResp}
	hdr = binary.BigEndian.AppendUint32(hdr, sid)
	hdr = append(hdr, spubB...)
	resp := append([]byte(nil), hdr...)
	resp = tx.Seal(resp, nonce(0), ip[:], hdr)
	s.conn.WriteToUDP(resp, from)
}

func (s *server) handleData(b []byte, from *net.UDPAddr) {
	if len(b) < 13+16 {
		return
	}
	sid := binary.BigEndian.Uint32(b[1:5])
	ctr := binary.BigEndian.Uint64(b[5:13])
	s.mu.RLock()
	sess := s.sessions[sid]
	s.mu.RUnlock()
	if sess == nil {
		return
	}
	sess.mu.Lock()
	okw := sess.win.ok(ctr)
	sess.mu.Unlock()
	if !okw {
		return
	}
	pt, err := sess.rx.Open(nil, nonce(ctr), b[13:], b[:13])
	if err != nil {
		return
	}
	sess.mu.Lock()
	sess.win.mark(ctr)
	sess.addr = from
	sess.last = time.Now()
	promote := !sess.active
	sess.active = true
	sess.mu.Unlock()
	if promote {
		s.mu.Lock()
		s.byIP[sess.ip] = sess
		s.mu.Unlock()
		log.Printf("istemci aktif: %d.%d.%d.%d <- %s", sess.ip[0], sess.ip[1], sess.ip[2], sess.ip[3], from)
	}
	if len(pt) == 0 {
		s.send(sess, nil) // keepalive yankısı
		return
	}
	if len(pt) < 20 || pt[0]>>4 != 4 || [4]byte(pt[12:16]) != sess.ip {
		return // IPv4 değil veya sahte kaynak adresi
	}
	s.tun.Write(pt)
}

func (s *server) send(sess *session, payload []byte) {
	ctr := sess.txCtr.Add(1)
	var hdr [13]byte
	hdr[0] = tData
	binary.BigEndian.PutUint32(hdr[1:], sess.sid)
	binary.BigEndian.PutUint64(hdr[5:], ctr)
	pkt := make([]byte, 0, 13+len(payload)+16)
	pkt = append(pkt, hdr[:]...)
	pkt = sess.tx.Seal(pkt, nonce(ctr), payload, hdr[:])
	sess.mu.Lock()
	a := sess.addr
	sess.mu.Unlock()
	if a != nil {
		s.conn.WriteToUDP(pkt, a)
	}
}

func (s *server) udpLoop() {
	buf := make([]byte, 2048)
	for {
		n, from, err := s.conn.ReadFromUDP(buf)
		if err != nil {
			log.Printf("udp: %v", err)
			continue
		}
		if n == 0 {
			continue
		}
		switch buf[0] {
		case tInit:
			s.handleInit(buf[:n], from)
		case tData:
			s.handleData(buf[:n], from)
		}
	}
}

func (s *server) tunLoop() {
	buf := make([]byte, 2048)
	for {
		n, err := s.tun.Read(buf)
		if err != nil {
			log.Fatalf("tun okuma: %v", err)
		}
		if n < 20 || buf[0]>>4 != 4 {
			continue
		}
		var dst [4]byte
		copy(dst[:], buf[16:20])
		s.mu.RLock()
		sess := s.byIP[dst]
		s.mu.RUnlock()
		if sess != nil {
			s.send(sess, buf[:n])
		}
	}
}

func (s *server) reaper() {
	for range time.Tick(10 * time.Second) {
		now := time.Now()
		s.mu.Lock()
		for sid, x := range s.sessions {
			x.mu.Lock()
			act, last, cr := x.active, x.last, x.created
			x.mu.Unlock()
			if (!act && now.Sub(cr) > 30*time.Second) || (act && now.Sub(last) > 180*time.Second) {
				delete(s.sessions, sid)
				if s.byIP[x.ip] == x {
					delete(s.byIP, x.ip)
				}
			}
		}
		s.mu.Unlock()
	}
}

// ---- Sistem kurulumu ----
func run(name string, args ...string) error {
	out, err := exec.Command(name, args...).CombinedOutput()
	if err != nil {
		return fmt.Errorf("%s %s: %v: %s", name, strings.Join(args, " "), err, strings.TrimSpace(string(out)))
	}
	return nil
}

type ipt struct {
	table, chain string
	spec         []string
}

func (r ipt) args(op string) []string {
	a := []string{}
	if r.table != "" {
		a = append(a, "-t", r.table)
	}
	a = append(a, op, r.chain)
	return append(a, r.spec...)
}
func (r ipt) apply() error {
	if run("iptables", r.args("-C")...) == nil {
		return nil
	}
	return run("iptables", r.args("-I")...)
}
func (r ipt) remove() { run("iptables", r.args("-D")...) }

func defaultIface() string {
	out, err := exec.Command("ip", "route", "get", "1.1.1.1").Output()
	if err != nil {
		return ""
	}
	f := strings.Fields(string(out))
	for i, w := range f {
		if w == "dev" && i+1 < len(f) {
			return f[i+1]
		}
	}
	return ""
}

func openTun(name string) (*os.File, string, error) {
	fd, err := unix.Open("/dev/net/tun", unix.O_RDWR, 0)
	if err != nil {
		return nil, "", err
	}
	ifr, err := unix.NewIfreq(name)
	if err != nil {
		unix.Close(fd)
		return nil, "", err
	}
	ifr.SetUint16(unix.IFF_TUN | unix.IFF_NO_PI)
	if err := unix.IoctlIfreq(fd, unix.TUNSETIFF, ifr); err != nil {
		unix.Close(fd)
		return nil, "", err
	}
	return os.NewFile(uintptr(fd), "/dev/net/tun"), ifr.Name(), nil
}

func main() {
	listen := flag.String("listen", ":51820", "UDP dinleme adresi")
	pskFlag := flag.String("psk", "", "base64 PSK (veya MYVPN_PSK ortam değişkeni)")
	tunName := flag.String("tun", "myvpn0", "TUN arayüz adı")
	nat := flag.Bool("nat", true, "IP forwarding + NAT kur")
	outIf := flag.String("out", "", "internete çıkış arayüzü (boşsa otomatik)")
	genkey := flag.Bool("genkey", false, "yeni PSK üret ve çık")
	linkHost := flag.String("link", "", "uygulama için içe aktarma bağlantısı yazdır (genel IP/alan adı)")
	linkName := flag.String("name", "Sunucum", "bağlantıdaki sunucu adı")
	linkFlag := flag.String("emoji", "🌐", "bağlantıdaki bayrak/emoji")
	flag.Parse()

	if *genkey {
		k := make([]byte, 32)
		rand.Read(k)
		fmt.Println(base64.StdEncoding.EncodeToString(k))
		return
	}
	pskStr := *pskFlag
	if pskStr == "" {
		pskStr = os.Getenv("MYVPN_PSK")
	}
	psk, err := base64.StdEncoding.DecodeString(strings.TrimSpace(pskStr))
	if err != nil || len(psk) != 32 {
		log.Fatal("PSK 32 baytlık base64 olmalı. Üretmek için: ./myvpn -genkey")
	}

	if *linkHost != "" {
		_, port, _ := net.SplitHostPort(*listen)
		if port == "" {
			port = "51820"
		}
		fmt.Printf("myvpn://%s:%s?psk=%s&name=%s&flag=%s\n", *linkHost, port,
			url.QueryEscape(base64.StdEncoding.EncodeToString(psk)),
			url.QueryEscape(*linkName), url.QueryEscape(*linkFlag))
		return
	}

	tun, name, err := openTun(*tunName)
	if err != nil {
		log.Fatalf("TUN açılamadı (root musun?): %v", err)
	}
	if err := run("ip", "addr", "add", serverIP, "dev", name); err != nil {
		log.Printf("uyarı: %v", err)
	}
	if err := run("ip", "link", "set", "dev", name, "mtu", fmt.Sprint(mtu), "up"); err != nil {
		log.Fatal(err)
	}

	var rules []ipt
	if *nat {
		out := *outIf
		if out == "" {
			out = defaultIface()
		}
		if out == "" {
			log.Fatal("çıkış arayüzü bulunamadı, -out verin")
		}
		os.WriteFile("/proc/sys/net/ipv4/ip_forward", []byte("1"), 0644)
		rules = []ipt{
			{"nat", "POSTROUTING", []string{"-s", subnet, "-o", out, "-j", "MASQUERADE"}},
			{"", "FORWARD", []string{"-i", name, "-j", "ACCEPT"}},
			{"", "FORWARD", []string{"-o", name, "-m", "conntrack", "--ctstate", "RELATED,ESTABLISHED", "-j", "ACCEPT"}},
			{"mangle", "FORWARD", []string{"-p", "tcp", "--tcp-flags", "SYN,RST", "SYN", "-j", "TCPMSS", "--clamp-mss-to-pmtu"}},
		}
		for _, r := range rules {
			if err := r.apply(); err != nil {
				log.Printf("iptables uyarı: %v", err)
			}
		}
		log.Printf("NAT açık: %s -> %s", subnet, out)
	}
	sig := make(chan os.Signal, 1)
	signal.Notify(sig, os.Interrupt, syscall.SIGTERM)
	go func() {
		<-sig
		for _, r := range rules {
			r.remove()
		}
		os.Exit(0)
	}()

	addr, err := net.ResolveUDPAddr("udp", *listen)
	if err != nil {
		log.Fatal(err)
	}
	conn, err := net.ListenUDP("udp", addr)
	if err != nil {
		log.Fatal(err)
	}
	s := &server{
		conn: conn, tun: tun, psk: psk,
		sessions: map[uint32]*session{},
		byIP:     map[[4]byte]*session{},
		clientIP: map[[8]byte][4]byte{},
		nextHost: 2,
	}
	log.Printf("sunucu hazır: %s, tun=%s, mtu=%d", *listen, name, mtu)
	go s.reaper()
	go s.tunLoop()
	s.udpLoop()
}
