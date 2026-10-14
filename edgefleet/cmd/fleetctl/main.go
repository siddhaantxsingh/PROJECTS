// fleetctl: administration CLI.
//
//	fleetctl keygen -out release.key                       # prints the public key
//	fleetctl release -key release.key -version 2.0 -file app [-sim-health ok]
//	fleetctl token
//	fleetctl rollout -version 2.0 [-waves 5,25,100] [-max-failure 0.2] [-bake 10m] [-from 1.0] [-selector k=v]
//	fleetctl status ROLLOUT_ID | pause ID | resume ID | abort ID
//	fleetctl devices
//
// -server and -token default to $FLEET_SERVER and $FLEET_ADMIN_TOKEN.
package main

import (
	"bytes"
	"crypto/ed25519"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"flag"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"os"
	"strconv"
	"strings"
	"time"

	"edgefleet/internal/proto"
)

func main() {
	if len(os.Args) < 2 {
		fmt.Fprintln(os.Stderr, "usage: fleetctl keygen|release|token|rollout|status|pause|resume|abort|devices ...")
		os.Exit(2)
	}
	cmd, args := os.Args[1], os.Args[2:]
	fs := flag.NewFlagSet(cmd, flag.ExitOnError)
	server := fs.String("server", env("FLEET_SERVER", "http://127.0.0.1:8080"), "control plane URL")
	token := fs.String("token", os.Getenv("FLEET_ADMIN_TOKEN"), "admin token")
	out := fs.String("out", "release.key", "keygen: private key file")
	keyf := fs.String("key", "release.key", "release: private key file")
	ver := fs.String("version", "", "release/rollout version")
	file := fs.String("file", "", "release: artifact file")
	sim := fs.String("sim-health", "", "release: simulated health for fleetsim devices")
	waves := fs.String("waves", "5,25,100", "rollout: cumulative wave percentages")
	maxf := fs.Float64("max-failure", 0.2, "rollout: failure rate that halts the rollout")
	bake := fs.Duration("bake", 0, "rollout: minimum time per wave")
	from := fs.String("from", "", "rollout: version to roll back to (default: most common)")
	minrep := fs.Float64("min-report", 0.9, "rollout: fraction of a wave that must report")
	sel := fs.String("selector", "", "rollout: k=v[,k=v] label selector")
	fs.Parse(args)
	call := func(method, path string, body []byte) {
		req, _ := http.NewRequest(method, *server+path, bytes.NewReader(body))
		req.Header.Set("Authorization", "Bearer "+*token)
		resp, err := http.DefaultClient.Do(req)
		if err != nil {
			fail(err)
		}
		defer resp.Body.Close()
		b, _ := io.ReadAll(resp.Body)
		var pretty bytes.Buffer
		if json.Indent(&pretty, b, "", "  ") == nil {
			b = pretty.Bytes()
		}
		fmt.Println(string(b))
		if resp.StatusCode >= 300 {
			os.Exit(1)
		}
	}
	switch cmd {
	case "keygen":
		pub, priv, _ := ed25519.GenerateKey(rand.Reader)
		if err := os.WriteFile(*out, priv, 0o600); err != nil {
			fail(err)
		}
		fmt.Println(base64.StdEncoding.EncodeToString(pub))
	case "release":
		priv, err := os.ReadFile(*keyf)
		if err != nil || len(priv) != ed25519.PrivateKeySize {
			fail(fmt.Errorf("bad key file %s", *keyf))
		}
		data, err := os.ReadFile(*file)
		if err != nil {
			fail(err)
		}
		h := sha256.Sum256(data)
		m := &proto.Manifest{Version: *ver, Size: int64(len(data)), SHA256: hex.EncodeToString(h[:]), SimHealth: *sim}
		q := url.Values{"version": {*ver}, "signature": {proto.SignManifest(ed25519.PrivateKey(priv), m)}, "sim_health": {*sim}}
		call("POST", "/v1/admin/releases?"+q.Encode(), data)
	case "token":
		call("POST", "/v1/admin/tokens", nil)
	case "rollout":
		var ws []map[string]float64
		for _, p := range strings.Split(*waves, ",") {
			v, err := strconv.ParseFloat(p, 64)
			if err != nil {
				fail(err)
			}
			ws = append(ws, map[string]float64{"percent": v})
		}
		selector := map[string]string{}
		if *sel != "" {
			for _, kv := range strings.Split(*sel, ",") {
				k, v, _ := strings.Cut(kv, "=")
				selector[k] = v
			}
		}
		call("POST", "/v1/admin/rollouts", proto.MustJSON(map[string]any{"version": *ver, "waves": ws, "max_failure_rate": *maxf,
			"bake_ms": bake.Milliseconds(), "from_version": *from, "min_report_fraction": *minrep, "selector": selector}))
	case "status":
		call("GET", "/v1/admin/rollouts/"+fs.Arg(0), nil)
	case "pause", "resume", "abort":
		call("POST", "/v1/admin/rollouts/"+fs.Arg(0)+"/"+cmd, nil)
	case "devices":
		call("GET", "/v1/admin/devices", nil)
	default:
		fail(fmt.Errorf("unknown command %s", cmd))
	}
	_ = time.Second
}

func env(k, d string) string {
	if v := os.Getenv(k); v != "" {
		return v
	}
	return d
}

func fail(err error) {
	fmt.Fprintln(os.Stderr, "fleetctl:", err)
	os.Exit(1)
}
