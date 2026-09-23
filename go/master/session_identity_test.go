package master

import (
	"log/slog"
	"testing"
	"time"

	"github.com/leehaohaohao/nexa-protocol/go/codec"
	"github.com/leehaohaohao/nexa-protocol/go/messages"
)

const goodToken = "good-token"

func testLogger() *slog.Logger {
	return slog.New(slog.DiscardHandler)
}

// newDispatchedMaster 组装与 NexaMaster 相同的 handler 集合
func newDispatchedMaster(sessions *SessionManager, listener Listener) *MessageDispatcher {
	return NewMessageDispatcher([]Handler{
		NewRegisterHandler(sessions, listener, testLogger()),
		NewHeartbeatHandler(sessions, listener, testLogger()),
		NewDisconnectHandler(sessions, listener, testLogger()),
		NewContainerStatusHandler(sessions, listener, testLogger()),
		NewContainerLogsHandler(sessions, listener, testLogger()),
		NewArtifactHandler(sessions, listener, testLogger()),
	}, testLogger())
}

// registerConn 通过 RegisterHandler 完成一次注册，返回该连接的 ConnContext
func registerConn(t *testing.T, dispatcher *MessageDispatcher, sessions *SessionManager, conn *mockConn, runnerId string) *ConnContext {
	t.Helper()

	connCtx := &ConnContext{Session: NewRunnerSession("", conn, "", "", "")}
	dispatcher.Dispatch(connCtx, registerEnvelope(runnerId, goodToken))

	if _, ok := sessions.Get(runnerId); !ok {
		t.Fatalf("session %q should be registered", runnerId)
	}
	return connCtx
}

func registerEnvelope(runnerId, token string) *messages.Envelope {
	return codec.BuildRegisterRequestWithToken(runnerId, runnerId+"-host", "127.0.0.1", "test", token)
}

func heartbeatEnvelope(runnerId string) *messages.Envelope {
	return codec.BuildHeartbeatRequest(runnerId, 0, 0, 0)
}

func disconnectEnvelope(runnerId, reason string) *messages.Envelope {
	return codec.BuildDisconnectRequest(runnerId, reason)
}

func statusEnvelope(runnerId string) *messages.Envelope {
	resp := &messages.ContainerStatusResponse{RunnerId: runnerId, Running: true, Status: "running"}
	return codec.BuildContainerStatusResponse("req-1", runnerId, resp)
}

func logsEnvelope(runnerId string) *messages.Envelope {
	resp := &messages.ContainerLogsResponse{RunnerId: runnerId, Content: "logs"}
	return codec.BuildContainerLogsResponse("req-1", runnerId, resp)
}

func artifactEnvelope(sourceId, serviceId string) *messages.Envelope {
	req := &messages.ArtifactRequest{ServiceId: serviceId, Type: "JAR", Version: 0}
	return codec.BuildArtifactRequest(sourceId, req)
}

// ---- 心跳身份校验 ----

// 未注册连接冒用已在线节点身份发心跳：不刷新任何会话、不回包、不回调
func TestUnregisteredConnectionCannotRefreshHeartbeat(t *testing.T) {
	sessions := NewSessionManager()
	listener := newRecordingListener(goodToken)
	dispatcher := newDispatchedMaster(sessions, listener)

	onlineConn := newMockConn()
	registerConn(t, dispatcher, sessions, onlineConn, "B")
	sessionB, _ := sessions.Get("B")
	before := sessionB.lastHeartbeatTime.Load()

	rogueConn := newMockConn()
	rogueCtx := &ConnContext{Session: NewRunnerSession("", rogueConn, "", "", "")}
	dispatcher.Dispatch(rogueCtx, heartbeatEnvelope("B"))

	if got := sessionB.lastHeartbeatTime.Load(); got != before {
		t.Fatalf("unregistered heartbeat must not refresh session B: before=%d after=%d", before, got)
	}
	if listener.heartbeatCount() != 0 {
		t.Fatalf("unregistered heartbeat must not reach business listener")
	}
	if rogueConn.bytesWritten() != 0 {
		t.Fatalf("unregistered connection must not receive a heartbeat response")
	}
}

// 已注册 A 冒用 B 的 runnerId 发心跳：A、B 心跳时间都不变，且都不回包
func TestRegisteredConnectionCannotHeartbeatAsAnotherRunner(t *testing.T) {
	sessions := NewSessionManager()
	listener := newRecordingListener(goodToken)
	dispatcher := newDispatchedMaster(sessions, listener)

	connA := newMockConn()
	ctxA := registerConn(t, dispatcher, sessions, connA, "A")
	connB := newMockConn()
	ctxB := registerConn(t, dispatcher, sessions, connB, "B")

	sessionA, _ := sessions.Get("A")
	sessionB, _ := sessions.Get("B")
	beforeA := sessionA.lastHeartbeatTime.Load()
	beforeB := sessionB.lastHeartbeatTime.Load()

	writtenABefore := connA.bytesWritten()
	writtenBBefore := connB.bytesWritten()

	dispatcher.Dispatch(ctxA, heartbeatEnvelope("B"))

	if sessionB.lastHeartbeatTime.Load() != beforeB {
		t.Fatalf("forged heartbeat must not refresh B")
	}
	if sessionA.lastHeartbeatTime.Load() != beforeA {
		t.Fatalf("forged heartbeat must not refresh A either")
	}
	if listener.heartbeatCount() != 0 {
		t.Fatalf("forged heartbeat must not reach business listener")
	}
	if connA.bytesWritten() != writtenABefore {
		t.Fatalf("forged heartbeat must not be answered to A")
	}
	if connB.bytesWritten() != writtenBBefore {
		t.Fatalf("B must not receive a response for A's forgery")
	}
	if ctxB.Session != sessionB {
		t.Fatalf("precondition: B context should hold session B")
	}
}

// 正常心跳：刷新自身会话并只回发给发送方
func TestNormalHeartbeatRefreshesOwnSession(t *testing.T) {
	sessions := NewSessionManager()
	listener := newRecordingListener(goodToken)
	dispatcher := newDispatchedMaster(sessions, listener)

	conn := newMockConn()
	ctx := registerConn(t, dispatcher, sessions, conn, "B")
	sessionB, _ := sessions.Get("B")
	ageHeartbeat(sessionB, 5*time.Second)
	before := sessionB.lastHeartbeatTime.Load()
	writtenBefore := conn.bytesWritten()

	dispatcher.Dispatch(ctx, heartbeatEnvelope("B"))

	if sessionB.lastHeartbeatTime.Load() <= before {
		t.Fatalf("normal heartbeat should refresh its own session")
	}
	if listener.heartbeatCount() != 1 {
		t.Fatalf("normal heartbeat should reach business listener exactly once")
	}
	if conn.bytesWritten() <= writtenBefore {
		t.Fatalf("sender should receive the heartbeat response")
	}
	if sessionB.timedOut.Load() {
		t.Fatalf("session should not be marked timed out")
	}
}

// ---- 主动断开身份校验 ----

// 未注册连接冒用 B 身份发 DISCONNECT：B 会话不受影响
func TestUnregisteredConnectionCannotDisconnectRunner(t *testing.T) {
	sessions := NewSessionManager()
	listener := newRecordingListener(goodToken)
	dispatcher := newDispatchedMaster(sessions, listener)

	registerConn(t, dispatcher, sessions, newMockConn(), "B")
	sessionB, _ := sessions.Get("B")
	listener.reset()

	rogueConn := newMockConn()
	rogueCtx := &ConnContext{Session: NewRunnerSession("", rogueConn, "", "", "")}
	dispatcher.Dispatch(rogueCtx, disconnectEnvelope("B", "bye"))

	if current, ok := sessions.Get("B"); !ok || current != sessionB {
		t.Fatalf("B session must survive forged disconnect")
	}
	if listener.disconnectCount("B") != 0 {
		t.Fatalf("business listener must not see B disconnect")
	}
	if !rogueConn.isClosed() {
		t.Fatalf("unregistered connection should be closed")
	}
}

// 已注册 A 冒用 B 身份发 DISCONNECT：只影响 A 自己，绝不移除 B
func TestRegisteredConnectionCannotDisconnectAnotherRunner(t *testing.T) {
	sessions := NewSessionManager()
	listener := newRecordingListener(goodToken)
	dispatcher := newDispatchedMaster(sessions, listener)

	ctxA := registerConn(t, dispatcher, sessions, newMockConn(), "A")
	connA := ctxA.Session.Conn.(*mockConn)
	registerConn(t, dispatcher, sessions, newMockConn(), "B")
	sessionB, _ := sessions.Get("B")
	listener.reset()

	dispatcher.Dispatch(ctxA, disconnectEnvelope("B", "bye"))

	if current, ok := sessions.Get("B"); !ok || current != sessionB {
		t.Fatalf("forged disconnect must not remove B")
	}
	if listener.disconnectCount("B") != 0 {
		t.Fatalf("B must not be reported disconnected")
	}
	// 伪造身份的消息被拒绝：关闭发送方自己的连接（会话清理由连接退出流程负责）
	if !connA.isClosed() {
		t.Fatalf("forged disconnect should close the sender's own connection")
	}
}

// 正常主动断开：条件移除自身会话并通知一次
func TestNormalDisconnectRemovesOwnSessionAndNotifiesOnce(t *testing.T) {
	sessions := NewSessionManager()
	listener := newRecordingListener(goodToken)
	dispatcher := newDispatchedMaster(sessions, listener)

	conn := newMockConn()
	ctx := registerConn(t, dispatcher, sessions, conn, "A")
	listener.reset()

	dispatcher.Dispatch(ctx, disconnectEnvelope("A", "shutdown"))

	if _, ok := sessions.Get("A"); ok {
		t.Fatalf("session should be removed on normal disconnect")
	}
	events := listener.disconnectsFor("A")
	if len(events) != 1 || events[0].reason != "shutdown" {
		t.Fatalf("expected exactly one shutdown disconnect event, got %+v", events)
	}
	if !conn.isClosed() {
		t.Fatalf("connection should be closed on normal disconnect")
	}
}

// ---- 回执与产物请求身份校验 ----

// 已注册 A 冒用 B 身份发状态/日志回执：业务层不以 B 身份处理
func TestRegisteredConnectionCannotForgeQueryResults(t *testing.T) {
	sessions := NewSessionManager()
	listener := newRecordingListener(goodToken)
	dispatcher := newDispatchedMaster(sessions, listener)

	ctxA := registerConn(t, dispatcher, sessions, newMockConn(), "A")
	registerConn(t, dispatcher, sessions, newMockConn(), "B")
	listener.reset()

	dispatcher.Dispatch(ctxA, statusEnvelope("B"))
	dispatcher.Dispatch(ctxA, logsEnvelope("B"))

	if listener.statusCount() != 0 || listener.logCount() != 0 {
		t.Fatalf("forged query results must not be processed as B")
	}
}

// 已注册 A 用 B 的 source_id 请求产物：按连接绑定识别为 A，不以 B 身份回调
func TestRegisteredConnectionCannotForgeArtifactIdentity(t *testing.T) {
	sessions := NewSessionManager()
	listener := newRecordingListener(goodToken)
	dispatcher := newDispatchedMaster(sessions, listener)

	ctxA := registerConn(t, dispatcher, sessions, newMockConn(), "A")
	registerConn(t, dispatcher, sessions, newMockConn(), "B")
	listener.reset()

	dispatcher.Dispatch(ctxA, artifactEnvelope("B", "svc-1"))

	if listener.artifactCount() != 0 {
		t.Fatalf("artifact request with mismatched source_id must be rejected")
	}
}

// 正常产物请求：按连接绑定识别身份
func TestNormalArtifactRequestIsAccepted(t *testing.T) {
	sessions := NewSessionManager()
	listener := newRecordingListener(goodToken)
	dispatcher := newDispatchedMaster(sessions, listener)

	ctx := registerConn(t, dispatcher, sessions, newMockConn(), "A")
	listener.reset()

	dispatcher.Dispatch(ctx, artifactEnvelope("A", "svc-1"))

	if listener.artifactCount() != 1 {
		t.Fatalf("normal artifact request should reach business listener")
	}
}

// ---- 接管后的旧连接 ----

// 旧连接被同 ID 新连接接管后继续发送已注册消息：一律拒绝，新会话不受影响
func TestSupersededConnectionMessagesAreRejected(t *testing.T) {
	sessions := NewSessionManager()
	listener := newRecordingListener(goodToken)
	dispatcher := newDispatchedMaster(sessions, listener)

	oldCtx := registerConn(t, dispatcher, sessions, newMockConn(), "R")
	stale := oldCtx.Session

	// 模拟新连接接管：注册表替换为新会话，旧 ConnContext 仍指向旧会话
	fresh := registeredSession(sessions, "R", newMockConn())
	ageHeartbeat(fresh, 5*time.Second)
	before := fresh.lastHeartbeatTime.Load()
	listener.reset()

	if sessions.IsCurrent(stale) {
		t.Fatalf("precondition: stale session should no longer be the current one")
	}

	dispatcher.Dispatch(oldCtx, heartbeatEnvelope("R"))
	dispatcher.Dispatch(oldCtx, disconnectEnvelope("R", "bye"))
	dispatcher.Dispatch(oldCtx, statusEnvelope("R"))
	dispatcher.Dispatch(oldCtx, artifactEnvelope("R", "svc-1"))

	if listener.heartbeatCount() != 0 {
		t.Fatalf("superseded connection heartbeat must be rejected")
	}
	if listener.statusCount() != 0 {
		t.Fatalf("superseded connection status must be rejected")
	}
	if listener.artifactCount() != 0 {
		t.Fatalf("superseded connection artifact request must be rejected")
	}
	if listener.disconnectCount("R") != 0 {
		t.Fatalf("superseded connection must not disconnect R")
	}
	if fresh.lastHeartbeatTime.Load() != before {
		t.Fatalf("new session must not be refreshed by the old connection")
	}
	if current, ok := sessions.Get("R"); !ok || current != fresh {
		t.Fatalf("new session must stay registered")
	}
}

// 接管后新会话一切正常
func TestSupersedingSessionKeepsWorking(t *testing.T) {
	sessions := NewSessionManager()
	listener := newRecordingListener(goodToken)
	dispatcher := newDispatchedMaster(sessions, listener)

	registerConn(t, dispatcher, sessions, newMockConn(), "R")

	// 新连接注册（走真实注册路径）
	newConn := newMockConn()
	newCtx := registerConn(t, dispatcher, sessions, newConn, "R")
	listener.reset()

	current, _ := sessions.Get("R")
	if current != newCtx.Session {
		t.Fatalf("registry should hold the new session")
	}

	writtenBefore := newConn.bytesWritten()
	dispatcher.Dispatch(newCtx, heartbeatEnvelope("R"))

	if listener.heartbeatCount() != 1 {
		t.Fatalf("new session should keep heartbeating")
	}
	if newConn.bytesWritten() <= writtenBefore {
		t.Fatalf("new session should receive its heartbeat response")
	}
}
