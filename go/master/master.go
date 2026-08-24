package master

import (
	"context"
	"fmt"
	"log/slog"
	"net"
	"sync"
	"time"

	"github.com/leehaohaohao/nexa-protocol/go/codec"
	"github.com/leehaohaohao/nexa-protocol/go/messages"
)

const (
	defaultHost              = "0.0.0.0"
	defaultPort              = 9090
	defaultHeartbeatTimeout  = 30 * time.Second
	defaultHeartbeatInterval = 5 * time.Second
)

// Option 函数式选项
type Option func(*NexaMaster)

// WithHost 设置监听地址
func WithHost(host string) Option {
	return func(m *NexaMaster) { m.host = host }
}

// WithPort 设置监听端口
func WithPort(port int) Option {
	return func(m *NexaMaster) { m.port = port }
}

// WithHeartbeatTimeout 设置心跳超时时间
func WithHeartbeatTimeout(d time.Duration) Option {
	return func(m *NexaMaster) { m.heartbeatTimeout = d }
}

// WithHeartbeatInterval 设置心跳检查间隔
func WithHeartbeatInterval(d time.Duration) Option {
	return func(m *NexaMaster) { m.heartbeatInterval = d }
}

// WithLogger 设置日志器
func WithLogger(logger *slog.Logger) Option {
	return func(m *NexaMaster) { m.logger = logger }
}

// NexaMaster 主节点
type NexaMaster struct {
	host              string
	port              int
	listener          Listener
	sessions          *SessionManager
	heartbeat         *HeartbeatMonitor
	dispatcher        *MessageDispatcher
	logger            *slog.Logger
	heartbeatTimeout  time.Duration
	heartbeatInterval time.Duration

	tcpListener net.Listener
	wg          sync.WaitGroup
	ctx         context.Context
	cancel      context.CancelFunc
}

// New 创建主节点
func New(listener Listener, opts ...Option) *NexaMaster {
	ctx, cancel := context.WithCancel(context.Background())

	m := &NexaMaster{
		host:              defaultHost,
		port:              defaultPort,
		listener:          listener,
		sessions:          NewSessionManager(),
		logger:            slog.Default(),
		heartbeatTimeout:  defaultHeartbeatTimeout,
		heartbeatInterval: defaultHeartbeatInterval,
		ctx:               ctx,
		cancel:            cancel,
	}

	for _, opt := range opts {
		opt(m)
	}

	m.heartbeat = NewHeartbeatMonitor(m.sessions, listener, m.heartbeatTimeout, m.heartbeatInterval, m.logger)

	handlers := []Handler{
		NewRegisterHandler(m.sessions, listener, m.logger),
		NewHeartbeatHandler(m.sessions, listener, m.logger),
		NewDisconnectHandler(m.sessions, listener, m.logger),
		NewContainerStatusHandler(m.sessions, listener, m.logger),
		NewContainerLogsHandler(m.sessions, listener, m.logger),
	}
	m.dispatcher = NewMessageDispatcher(handlers, m.logger)

	return m
}

// Start 启动主节点
func (m *NexaMaster) Start() error {
	addr := fmt.Sprintf("%s:%d", m.host, m.port)
	ln, err := net.Listen("tcp", addr)
	if err != nil {
		return fmt.Errorf("listen %s: %w", addr, err)
	}
	m.tcpListener = ln

	m.heartbeat.Start(m.ctx)

	m.logger.Info("nexa master started", "addr", addr)

	m.wg.Add(1)
	go m.acceptLoop()

	return nil
}

func (m *NexaMaster) acceptLoop() {
	defer m.wg.Done()

	for {
		conn, err := m.tcpListener.Accept()
		if err != nil {
			select {
			case <-m.ctx.Done():
				return
			default:
				m.logger.Error("accept error", "error", err)
				continue
			}
		}

		m.wg.Add(1)
		go func() {
			defer m.wg.Done()
			m.handleConn(conn)
		}()
	}
}

func (m *NexaMaster) handleConn(conn net.Conn) {
	defer conn.Close()

	connCtx := &ConnContext{
		Session: NewRunnerSession("", conn, "", "", ""),
	}

	defer func() {
		if connCtx.Session != nil && connCtx.Session.RunnerId != "" {
			m.sessions.RemoveIfPresent(connCtx.Session.RunnerId, connCtx.Session)
			if !connCtx.Session.timedOut.Load() {
				m.listener.OnDisconnect(connCtx.Session.RunnerId, "connection_lost")
			}
		}
	}()

	for {
		data, err := codec.ReadFrame(conn)
		if err != nil {
			return
		}

		env, err := codec.UnmarshalEnvelope(data)
		if err != nil {
			m.logger.Error("unmarshal envelope error", "error", err)
			return
		}

		m.dispatcher.Dispatch(connCtx, env)
	}
}

// Shutdown 优雅关闭主节点
func (m *NexaMaster) Shutdown() {
	m.cancel()

	if m.tcpListener != nil {
		m.tcpListener.Close()
	}

	for _, session := range m.sessions.AllSessions() {
		session.Close()
	}

	m.wg.Wait()
	m.logger.Info("nexa master stopped")
}

// Broadcast 向所有会话广播消息
func (m *NexaMaster) Broadcast(env *messages.Envelope) {
	for _, session := range m.sessions.AllSessions() {
		session.Send(env)
	}
}

// SendTo 向指定 runner 发送消息
func (m *NexaMaster) SendTo(runnerId string, env *messages.Envelope) bool {
	session, ok := m.sessions.Get(runnerId)
	if !ok {
		return false
	}
	return session.Send(env)
}

// SessionCount 返回当前会话数量
func (m *NexaMaster) SessionCount() int {
	return m.sessions.Size()
}
