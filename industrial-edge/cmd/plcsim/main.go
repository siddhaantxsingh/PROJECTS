// plcsim: a Modbus TCP PLC simulating a tank process, for tests and demos.
//
//	plcsim -listen 127.0.0.1:5020 -hz 50
//
// Register map (input registers, float32 big-endian word order):
//
//	0-1  level %        2-3  temperature °C     4-5  outflow m³/h
//	6-7  sample counter (uint32, +1 per update)
//
// Holding register 0: outflow valve position 0-100 % (writable).
// Coil 0: inflow pump running (controlled by the simulated PLC logic with
// 40-60 % hysteresis; writable to force).
// -spike-every N injects a +25 °C sensor spike (5 updates long) every N updates.
package main

import (
	"flag"
	"log"
	"os"
	"os/signal"
	"syscall"

	"industrial-edge/internal/plcsim"
)

func main() {
	addr := flag.String("listen", "127.0.0.1:5020", "listen address")
	hz := flag.Float64("hz", 50, "process update rate")
	spike := flag.Int("spike-every", 0, "inject a temperature spike every N updates (0 = never)")
	seed := flag.Int64("seed", 1, "noise seed")
	flag.Parse()
	p := plcsim.New(*seed, *spike)
	a, err := p.Listen(*addr, *hz)
	if err != nil {
		log.Fatal(err)
	}
	log.Printf("plcsim listening on %s", a)
	sig := make(chan os.Signal, 1)
	signal.Notify(sig, syscall.SIGINT, syscall.SIGTERM)
	<-sig
	p.Close()
}
