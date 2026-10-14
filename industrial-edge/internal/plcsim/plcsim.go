// Package plcsim simulates a tank process behind a Modbus TCP server.
package plcsim

import (
	"math"
	"math/rand"
	"sync"
	"time"

	"industrial-edge/internal/modbus"
)

// Plant is the simulated process.
type Plant struct {
	Server  *modbus.Server
	mu      sync.Mutex
	rng     *rand.Rand
	level   float64 // %
	temp    float64 // °C
	pump    bool
	counter uint32
	spike   int
	stop    chan struct{}
	wg      sync.WaitGroup
}

// New creates the plant (not yet serving).
func New(seed int64, spikeEvery int) *Plant {
	m := modbus.NewDataModel(64)
	m.Holding[0] = 50 // valve 50 %
	return &Plant{Server: &modbus.Server{Model: m}, rng: rand.New(rand.NewSource(seed)), level: 50, temp: 40, spike: spikeEvery,
		stop: make(chan struct{})}
}

func f32(v float64) [2]uint16 {
	b := math.Float32bits(float32(v))
	return [2]uint16{uint16(b >> 16), uint16(b)}
}

// Step advances the process by dt seconds and publishes the registers.
func (p *Plant) Step(dt float64) {
	p.mu.Lock()
	defer p.mu.Unlock()
	m := p.Server.Model
	m.Update(func(m *modbus.DataModel) {
		valve := float64(m.Holding[0]) / 100
		if p.level < 40 {
			p.pump = true
		} else if p.level > 60 {
			p.pump = false
		}
		in := 0.0
		if p.pump {
			in = 12
		}
		out := 10 * valve * math.Sqrt(math.Max(p.level, 0)/50)
		p.level += (in - out) * dt * 0.5
		p.level = math.Max(0, math.Min(100, p.level))
		p.temp += (45 - p.temp) * 0.01 * dt * 50
		p.counter++
		temp := p.temp + p.rng.NormFloat64()*0.05
		if p.spike > 0 && p.counter%uint32(p.spike) < 5 { // 5 updates long, so polling sees it
			temp += 25
		}
		lv := p.level + p.rng.NormFloat64()*0.02
		for i, v := range [][2]uint16{f32(lv), f32(temp), f32(out)} {
			m.Input[2*i], m.Input[2*i+1] = v[0], v[1]
		}
		m.Input[6], m.Input[7] = uint16(p.counter>>16), uint16(p.counter)
		m.Coils[0] = p.pump
	})
}

// Listen serves Modbus on addr and steps the process at hz.
func (p *Plant) Listen(addr string, hz float64) (string, error) {
	a, err := p.Server.Listen(addr)
	if err != nil {
		return "", err
	}
	p.wg.Add(1)
	go func() {
		defer p.wg.Done()
		dt := 1 / hz
		tk := time.NewTicker(time.Duration(dt * float64(time.Second)))
		defer tk.Stop()
		for {
			select {
			case <-p.stop:
				return
			case <-tk.C:
				p.Step(dt)
			}
		}
	}()
	return a, nil
}

// Close stops the process and the server.
func (p *Plant) Close() {
	close(p.stop)
	p.wg.Wait()
	p.Server.Close()
}
