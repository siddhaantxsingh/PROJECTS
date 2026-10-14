// fleetd: the fleet control plane.
//
//	FLEET_ADMIN_TOKEN=... fleetd -data ./fleet -listen 127.0.0.1:8080 -release-pubkey <base64>
package main

import (
	"context"
	"flag"
	"log"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"edgefleet/internal/proto"
	"edgefleet/internal/server"
)

func main() {
	data := flag.String("data", "fleet-data", "state directory")
	listen := flag.String("listen", "127.0.0.1:8080", "listen address")
	pubkey := flag.String("release-pubkey", "", "base64 Ed25519 public key that signs releases")
	hb := flag.Int("heartbeat-sec", 30, "device heartbeat interval")
	tick := flag.Duration("tick", time.Second, "rollout controller interval")
	flag.Parse()
	key, err := proto.DecodePublicKey(*pubkey)
	if err != nil {
		log.Fatal("-release-pubkey: ", err)
	}
	s, err := server.New(server.Config{DataDir: *data, AdminToken: os.Getenv("FLEET_ADMIN_TOKEN"), ReleaseKey: key,
		HeartbeatSec: *hb, TickInterval: *tick})
	if err != nil {
		log.Fatal(err)
	}
	srv := &http.Server{Addr: *listen, Handler: s.Handler(), ReadHeaderTimeout: 10 * time.Second}
	go func() {
		if err := srv.ListenAndServe(); err != nil && err != http.ErrServerClosed {
			log.Fatal(err)
		}
	}()
	log.Printf("fleetd listening on %s", *listen)
	sig := make(chan os.Signal, 1)
	signal.Notify(sig, syscall.SIGINT, syscall.SIGTERM)
	<-sig
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	srv.Shutdown(ctx)
	cancel()
	s.Close()
}
