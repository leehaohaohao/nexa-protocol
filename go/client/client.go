package client

import (
	"context"
	"fmt"
	"net"
	"os"
	"sync"
	"time"

	"github.com/leehaohaohao/nexa-protocol/go/codec"
	"github.com/leehaohaohao/nexa-protocol/go/messages"
)

// Client TCP 客户端，用于与主节点通信
type Client struct {
	conn      net.Conn
	runnerId  string
	hostname  string
	ip        string
	version   string
	heartbeat time.Duration
	stopCh    chan struct{}
	once      sync.Once
}

// Option 函数式选项
type Option func(*Client)

// WithRunnerId 设置 runner ID
func WithRunnerId(id string) Option {
	return func(c *Client) { c.runnerId = id }
}

// WithHostname 设置主机名
func WithHostname(h string) Option {
	return func(c *Client) { c.hostname = h }
}

// WithIP 设置 IP
func WithIP(ip string) Option {
	return func(c *Client) { c.ip = ip }
}

// WithVersion 设置版本号
func WithVersion(v string) Option {
	return func(c *Client) { c.version = v }
}

// WithHeartbeatInterval 设置心跳间隔
func WithHeartbeatInterval(d time.Duration) Option {
	return func(c *Client) { c.heartbeat = d }
}

// New 创建客户端
func New(opts ...Option) *Client {
	c := &Client{
		heartbeat: 10 * time.Second,
		stopCh:    make(chan struct{}),
	}
	for _, opt := range opts {
		opt(c)
	}
	// 默认用主机名
	if c.hostname == "" {
		c.hostname, _ = os.Hostname()
	}
	return c
}

// Connect 建立 TCP 连接
func (c *Client) Connect(addr string) error {
	conn, err := net.DialTimeout("tcp", addr, 5*time.Second)
	if err != nil {
		return fmt.Errorf("connect failed: %w", err)
	}
	c.conn = conn
	return nil
}

// Register 发送注册请求并等待响应
func (c *Client) Register() (*messages.RegisterResponse, error) {
	env := codec.BuildRegisterRequest(c.runnerId, c.hostname, c.ip, c.version)
	if err := c.sendEnvelope(env); err != nil {
		return nil, err
	}

	respEnv, err := c.ReadEnvelope()
	if err != nil {
		return nil, fmt.Errorf("read register response: %w", err)
	}

	if respEnv.GetType() != messages.MessageType_REGISTER_RESP {
		return nil, fmt.Errorf("unexpected response type: %v", respEnv.GetType())
	}

	resp := &messages.RegisterResponse{}
	if err := codec.UnmarshalMessage(respEnv.GetPayload(), resp); err != nil {
		return nil, fmt.Errorf("unmarshal register response: %w", err)
	}
	return resp, nil
}

// StartHeartbeat 启动定时心跳协程
func (c *Client) StartHeartbeat(ctx context.Context) {
	go func() {
		ticker := time.NewTicker(c.heartbeat)
		defer ticker.Stop()
		for {
			select {
			case <-ctx.Done():
				return
			case <-c.stopCh:
				return
			case <-ticker.C:
				env := codec.BuildHeartbeatRequest(c.runnerId, 0, 0, 0)
				if err := c.sendEnvelope(env); err != nil {
					return
				}
			}
		}
	}()
}

// Disconnect 发送断开消息并关闭连接
func (c *Client) Disconnect(reason string) error {
	c.once.Do(func() { close(c.stopCh) })

	env := codec.BuildDisconnectRequest(c.runnerId, reason)
	// best-effort 发送
	_ = c.sendEnvelope(env)

	if c.conn != nil {
		return c.conn.Close()
	}
	return nil
}

// ReadEnvelope 从连接中读取一个 Envelope
func (c *Client) ReadEnvelope() (*messages.Envelope, error) {
	data, err := codec.ReadFrame(c.conn)
	if err != nil {
		return nil, fmt.Errorf("read frame: %w", err)
	}
	return codec.UnmarshalEnvelope(data)
}

// Conn 获取底层连接，供业务层使用
func (c *Client) Conn() net.Conn {
	return c.conn
}

func (c *Client) sendEnvelope(env *messages.Envelope) error {
	data, err := codec.MarshalEnvelope(env)
	if err != nil {
		return fmt.Errorf("marshal envelope: %w", err)
	}
	return codec.WriteFrame(c.conn, data)
}
