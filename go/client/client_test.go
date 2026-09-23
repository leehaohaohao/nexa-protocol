package client_test

import (
	"context"
	"errors"
	"net"
	"testing"
	"time"

	"github.com/leehaohaohao/nexa-protocol/go/client"
	"github.com/leehaohaohao/nexa-protocol/go/codec"
	"github.com/leehaohaohao/nexa-protocol/go/messages"
)

// silentMaster 接受连接并持续读取，但从不回响应，用于模拟「主节点接受 TCP 但不回注册响应」
func silentMaster(t *testing.T) (addr string, cleanup func()) {
	t.Helper()

	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("listen: %v", err)
	}

	done := make(chan struct{})
	go func() {
		for {
			conn, err := ln.Accept()
			if err != nil {
				return
			}
			go func(c net.Conn) {
				defer c.Close()
				buf := make([]byte, 1024)
				for {
					if _, err := c.Read(buf); err != nil {
						return
					}
				}
			}(conn)
		}
	}()

	return ln.Addr().String(), func() {
		close(done)
		ln.Close()
	}
}

// TestRegisterContextTimesOutWhenMasterSilent 主节点不回注册响应时应超时返回，而非永久阻塞
func TestRegisterContextTimesOutWhenMasterSilent(t *testing.T) {
	addr, cleanup := silentMaster(t)
	defer cleanup()

	c := client.New(client.WithRunnerId("runner-1"))
	if err := c.Connect(addr); err != nil {
		t.Fatalf("connect: %v", err)
	}
	defer c.Close()

	ctx, cancel := context.WithTimeout(context.Background(), 300*time.Millisecond)
	defer cancel()

	start := time.Now()
	_, err := c.RegisterContext(ctx)
	elapsed := time.Since(start)

	if err == nil {
		t.Fatal("expected register to fail on timeout, got nil error")
	}
	if !errors.Is(err, context.DeadlineExceeded) {
		t.Fatalf("expected context.DeadlineExceeded, got %v", err)
	}
	if elapsed > 2*time.Second {
		t.Fatalf("register did not return promptly, elapsed=%v", elapsed)
	}
}

// TestRegisterContextCancelable ctx 取消应能立即打断等待
func TestRegisterContextCancelable(t *testing.T) {
	addr, cleanup := silentMaster(t)
	defer cleanup()

	c := client.New(client.WithRunnerId("runner-1"))
	if err := c.Connect(addr); err != nil {
		t.Fatalf("connect: %v", err)
	}
	defer c.Close()

	ctx, cancel := context.WithCancel(context.Background())
	go func() {
		time.Sleep(100 * time.Millisecond)
		cancel()
	}()

	start := time.Now()
	_, err := c.RegisterContext(ctx)
	elapsed := time.Since(start)

	if !errors.Is(err, context.Canceled) {
		t.Fatalf("expected context.Canceled, got %v", err)
	}
	if elapsed > 2*time.Second {
		t.Fatalf("cancel did not interrupt promptly, elapsed=%v", elapsed)
	}
}

// TestResetAllowsCleanReconnect Reset 后应能建立干净的新会话
func TestResetAllowsCleanReconnect(t *testing.T) {
	addr1, cleanup1 := silentMaster(t)
	defer cleanup1()
	addr2, cleanup2 := silentMaster(t)
	defer cleanup2()

	c := client.New(client.WithRunnerId("runner-1"))
	if err := c.Connect(addr1); err != nil {
		t.Fatalf("connect #1: %v", err)
	}
	if c.Conn() == nil {
		t.Fatal("expected connection after first connect")
	}

	if err := c.Reset(); err != nil {
		t.Fatalf("reset: %v", err)
	}
	if c.Conn() != nil {
		t.Fatal("expected connection to be cleared after reset")
	}

	// 重新连接第二个地址，验证会话可重建
	if err := c.Connect(addr2); err != nil {
		t.Fatalf("connect #2: %v", err)
	}
	if c.Conn() == nil {
		t.Fatal("expected connection after reconnect")
	}
}

// TestCloseIsIdempotent Close 应可重复调用且不 panic
func TestCloseIsIdempotent(t *testing.T) {
	addr, cleanup := silentMaster(t)
	defer cleanup()

	c := client.New(client.WithRunnerId("runner-1"))
	if err := c.Connect(addr); err != nil {
		t.Fatalf("connect: %v", err)
	}

	if err := c.Close(); err != nil {
		t.Fatalf("close #1: %v", err)
	}
	if err := c.Close(); err != nil {
		t.Fatalf("close #2: %v", err)
	}
}

// TestSendWithoutConnection 未连接时发送应返回明确错误而非 panic
func TestSendWithoutConnection(t *testing.T) {
	c := client.New(client.WithRunnerId("runner-1"))
	if err := c.Disconnect("bye"); err != nil {
		t.Fatalf("disconnect on idle client should be safe, got %v", err)
	}
}

// TestRegisterCarriesToken token 应随注册请求发送（L1 认证契约）
func TestRegisterCarriesToken(t *testing.T) {
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("listen: %v", err)
	}
	defer ln.Close()

	received := make(chan *messages.RegisterRequest, 1)
	go func() {
		conn, err := ln.Accept()
		if err != nil {
			return
		}
		defer conn.Close()

		data, err := codec.ReadFrame(conn)
		if err != nil {
			return
		}
		env, err := codec.UnmarshalEnvelope(data)
		if err != nil {
			return
		}
		req := &messages.RegisterRequest{}
		if err := codec.UnmarshalMessage(env.GetPayload(), req); err != nil {
			return
		}
		received <- req

		// 回一个成功响应，让 Register 正常返回
		resp := codec.BuildRegisterResponse(req.GetRunnerId(), true, "ok")
		out, _ := codec.MarshalEnvelope(resp)
		_ = codec.WriteFrame(conn, out)
	}()

	c := client.New(
		client.WithRunnerId("runner-token"),
		client.WithToken("secret-token-abc"),
		client.WithHostname("host-1"),
	)
	if err := c.Connect(ln.Addr().String()); err != nil {
		t.Fatalf("connect: %v", err)
	}
	defer c.Close()

	resp, err := c.Register()
	if err != nil {
		t.Fatalf("register: %v", err)
	}
	if !resp.GetSuccess() {
		t.Fatalf("expected successful register, got %v", resp)
	}

	select {
	case req := <-received:
		if req.GetToken() != "secret-token-abc" {
			t.Fatalf("expected token propagated, got %q", req.GetToken())
		}
		if req.GetRunnerId() != "runner-token" {
			t.Fatalf("expected runner id propagated, got %q", req.GetRunnerId())
		}
	case <-time.After(2 * time.Second):
		t.Fatal("master did not receive register request")
	}
}
