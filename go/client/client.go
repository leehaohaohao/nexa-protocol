package client

import (
	"context"
	"errors"
	"fmt"
	"net"
	"os"
	"sync"
	"time"

	"github.com/leehaohaohao/nexa-protocol/go/codec"
	"github.com/leehaohaohao/nexa-protocol/go/messages"
)

const (
	defaultDialTimeout     = 5 * time.Second
	defaultRegisterTimeout = 10 * time.Second
	defaultHeartbeat       = 10 * time.Second
)

// ErrNotConnected 当前没有可用连接
var ErrNotConnected = errors.New("client not connected")

// Client TCP 客户端，用于与主节点通信。
//
// 会话复用约束：一个 Client 实例同一时刻只持有一个连接，
// 每次重新连接前应调用 Reset（或 Close）清理上一个会话的状态，
// 避免旧连接被新流程复用或旧心跳向新连接回写。
type Client struct {
	mu      sync.Mutex // 保护 conn / stopCh / stopDone
	writeMu sync.Mutex // 串行化帧写入，避免心跳、注册、断开并发写交织

	conn      net.Conn
	runnerId  string
	hostname  string
	ip        string
	version   string
	token     string
	heartbeat time.Duration

	stopCh     chan struct{}
	stopDone   bool
	errHandler func(error)
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

// WithToken 设置注册 token（L1 注册认证，协议 v0.5.0）
func WithToken(token string) Option {
	return func(c *Client) { c.token = token }
}

// WithHeartbeatInterval 设置心跳间隔
func WithHeartbeatInterval(d time.Duration) Option {
	return func(c *Client) { c.heartbeat = d }
}

// WithErrorHandler 设置后台错误回调（如心跳写失败），便于连接管理循环统一处理
func WithErrorHandler(h func(error)) Option {
	return func(c *Client) { c.errHandler = h }
}

// New 创建客户端
func New(opts ...Option) *Client {
	c := &Client{
		heartbeat: defaultHeartbeat,
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

// Connect 建立 TCP 连接（默认拨号超时 5 秒）
func (c *Client) Connect(addr string) error {
	ctx, cancel := context.WithTimeout(context.Background(), defaultDialTimeout)
	defer cancel()
	return c.ConnectContext(ctx, addr)
}

// ConnectContext 在 ctx 控制下建立 TCP 连接，支持取消与有界超时。
// 连接成功前不会替换当前连接。
func (c *Client) ConnectContext(ctx context.Context, addr string) error {
	var dialer net.Dialer
	conn, err := dialer.DialContext(ctx, "tcp", addr)
	if err != nil {
		return fmt.Errorf("connect failed: %w", err)
	}

	c.mu.Lock()
	c.conn = conn
	c.mu.Unlock()
	return nil
}

// Register 发送注册请求并等待响应（默认超时 10 秒）
func (c *Client) Register() (*messages.RegisterResponse, error) {
	ctx, cancel := context.WithTimeout(context.Background(), defaultRegisterTimeout)
	defer cancel()
	return c.RegisterContext(ctx)
}

// RegisterContext 在 ctx 控制下发送注册请求并等待响应。
// 主节点接受连接但不回响应时，ctx 超时会释放本次等待（连接仍由调用方关闭或 Reset）。
func (c *Client) RegisterContext(ctx context.Context) (*messages.RegisterResponse, error) {
	env := codec.BuildRegisterRequestWithToken(c.runnerId, c.hostname, c.ip, c.version, c.token)
	if err := c.sendEnvelope(env); err != nil {
		return nil, err
	}

	respEnv, err := c.readEnvelopeContext(ctx)
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
	// 主节点拒绝注册（success=false，如 token 错误或节点未登记）时返回错误而非继续运行
	if !resp.GetSuccess() {
		return resp, fmt.Errorf("register rejected by master: %s", resp.GetMessage())
	}
	return resp, nil
}

// StartHeartbeat 启动定时心跳协程，通过 ctx 或 Disconnect/Reset 停止。
// 写失败时回调 WithErrorHandler 设置的处理函数（便于上层重连），并退出协程。
func (c *Client) StartHeartbeat(ctx context.Context) {
	go func() {
		ticker := time.NewTicker(c.heartbeat)
		defer ticker.Stop()
		for {
			select {
			case <-ctx.Done():
				return
			case <-c.stopSignal():
				return
			case <-ticker.C:
				env := codec.BuildHeartbeatRequest(c.runnerId, 0, 0, 0)
				if err := c.sendEnvelope(env); err != nil {
					c.reportError(fmt.Errorf("heartbeat failed: %w", err))
					return
				}
			}
		}
	}()
}

// Disconnect 发送断开消息并关闭连接。可重复调用。
func (c *Client) Disconnect(reason string) error {
	c.stopHeartbeat()

	env := codec.BuildDisconnectRequest(c.runnerId, reason)
	// best-effort 发送
	_ = c.sendEnvelope(env)

	return c.Close()
}

// Close 安全关闭当前连接（幂等），使 Client 可再次 Connect 建立新会话
func (c *Client) Close() error {
	c.mu.Lock()
	conn := c.conn
	c.conn = nil
	c.mu.Unlock()

	if conn == nil {
		return nil
	}
	return conn.Close()
}

// Reset 关闭当前连接并重建心跳停止状态，为下一次连接尝试准备干净的会话
func (c *Client) Reset() error {
	err := c.Close()

	c.mu.Lock()
	c.stopCh = make(chan struct{})
	c.stopDone = false
	c.mu.Unlock()

	return err
}

// ReadEnvelope 从连接中读取一个 Envelope（无超时，由调用方控制读循环）
func (c *Client) ReadEnvelope() (*messages.Envelope, error) {
	conn := c.Conn()
	if conn == nil {
		return nil, ErrNotConnected
	}
	data, err := codec.ReadFrame(conn)
	if err != nil {
		return nil, fmt.Errorf("read frame: %w", err)
	}
	return codec.UnmarshalEnvelope(data)
}

// Conn 获取底层连接，供业务层使用
func (c *Client) Conn() net.Conn {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.conn
}

// RunnerId 返回配置的 runner ID
func (c *Client) RunnerId() string {
	return c.runnerId
}

// readEnvelopeContext 读取一个 Envelope，ctx 取消或超时时立即中断阻塞读
func (c *Client) readEnvelopeContext(ctx context.Context) (*messages.Envelope, error) {
	conn := c.Conn()
	if conn == nil {
		return nil, ErrNotConnected
	}

	// ctx 结束时立即打断阻塞读
	done := make(chan struct{})
	defer close(done)
	go func() {
		select {
		case <-ctx.Done():
			_ = conn.SetReadDeadline(time.Now())
		case <-done:
		}
	}()

	if deadline, ok := ctx.Deadline(); ok {
		_ = conn.SetReadDeadline(deadline)
	}

	data, err := codec.ReadFrame(conn)
	// 清除本次读超时，不影响后续读取循环
	_ = conn.SetReadDeadline(time.Time{})
	if err != nil {
		if ctxErr := ctx.Err(); ctxErr != nil {
			return nil, ctxErr
		}
		return nil, fmt.Errorf("read frame: %w", err)
	}
	return codec.UnmarshalEnvelope(data)
}

func (c *Client) sendEnvelope(env *messages.Envelope) error {
	conn := c.Conn()
	if conn == nil {
		return ErrNotConnected
	}

	data, err := codec.MarshalEnvelope(env)
	if err != nil {
		return fmt.Errorf("marshal envelope: %w", err)
	}

	// 串行化写入，避免多协程并发写同一连接导致帧交织
	c.writeMu.Lock()
	defer c.writeMu.Unlock()
	return codec.WriteFrame(conn, data)
}

// stopSignal 返回心跳停止信号通道
func (c *Client) stopSignal() <-chan struct{} {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.stopCh
}

// stopHeartbeat 触发心跳停止（幂等）
func (c *Client) stopHeartbeat() {
	c.mu.Lock()
	defer c.mu.Unlock()
	if !c.stopDone {
		close(c.stopCh)
		c.stopDone = true
	}
}

func (c *Client) reportError(err error) {
	if c.errHandler != nil {
		c.errHandler(err)
	}
}
