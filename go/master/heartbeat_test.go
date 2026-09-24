package master

import (
	"testing"
	"time"

	"github.com/leehaohaohao/nexa-protocol/go/messages"
)

// alwaysExpired 用负超时让任何会话都被判定过期，避免依赖 sleep
const alwaysExpired = -1000 * time.Millisecond

func newTestMonitor(sessions *SessionManager, listener Listener) *HeartbeatMonitor {
	return NewHeartbeatMonitor(sessions, listener, alwaysExpired, 5*time.Second, testLogger())
}

// 超时会话：条件移除成功 → 关闭并通知一次
func TestTimeoutRemovesSessionAndNotifiesOnce(t *testing.T) {
	sessions := NewSessionManager()
	listener := newRecordingListener(goodToken)

	conn := newMockConn()
	session := registeredSession(sessions, "R", conn)

	newTestMonitor(sessions, listener).doCheck()

	if _, ok := sessions.Get("R"); ok {
		t.Fatalf("timed out session should be removed")
	}
	if !session.timedOut.Load() {
		t.Fatalf("session should be marked timed out")
	}
	if !conn.isClosed() {
		t.Fatalf("timed out connection should be closed")
	}
	events := listener.disconnectsFor("R")
	if len(events) != 1 || events[0].reason != "heartbeat_timeout" {
		t.Fatalf("expected exactly one heartbeat_timeout event, got %+v", events)
	}
	if events[0].session != session {
		t.Fatalf("disconnect event should carry the removed session identity")
	}
}

// 重复扫描不会对同一会话重复通知
func TestRepeatedScansNotifyOnlyOnce(t *testing.T) {
	sessions := NewSessionManager()
	listener := newRecordingListener(goodToken)

	registeredSession(sessions, "R", newMockConn())

	monitor := newTestMonitor(sessions, listener)
	monitor.doCheck()
	monitor.doCheck()
	monitor.doCheck()

	if got := listener.disconnectCount("R"); got != 1 {
		t.Fatalf("timeout must be notified exactly once, got %d", got)
	}
}

// 已被新连接接管的过期旧连接：只清理旧连接，不通知新节点掉线
func TestSupersededExpiredSessionIsClosedWithoutNotification(t *testing.T) {
	sessions := NewSessionManager()
	listener := newRecordingListener(goodToken)

	// 旧会话过期并被注册，随后被新会话接管
	staleConn := newMockConn()
	stale := registeredSession(sessions, "R", staleConn)
	ageHeartbeat(stale, time.Hour)

	// 让 doCheck 遍历到旧会话：AllSessions 返回快照后可被替换
	// 这里直接构造「快照含旧会话、注册表已是新会话」的交错状态
	snapshot := sessions.AllSessions()
	if len(snapshot) != 1 {
		t.Fatalf("precondition: expected one session in snapshot")
	}

	freshConn := newMockConn()
	registeredSession(sessions, "R", freshConn)

	if sessions.IsCurrent(stale) {
		t.Fatalf("precondition: stale session should be superseded")
	}
	if sessions.RemoveIfPresent("R", stale) {
		t.Fatalf("conditional removal must fail for a superseded session")
	}

	// 新会话心跳新鲜：在真实超时（1 秒）下不会被处理
	monitor := NewHeartbeatMonitor(sessions, listener, time.Second, 5*time.Second, testLogger())
	monitor.doCheck()

	if _, ok := sessions.Get("R"); !ok {
		t.Fatalf("fresh session must stay registered")
	}
	if freshConn.isClosed() {
		t.Fatalf("fresh connection must stay open")
	}
	if listener.disconnectCount("R") != 0 {
		t.Fatalf("superseded session must not be reported offline")
	}
}

// 超时与连接退出不得双重通知
func TestTimeoutThenConnectionCloseNotifiesOnlyOnce(t *testing.T) {
	sessions := NewSessionManager()
	listener := newRecordingListener(goodToken)

	conn := newMockConn()
	session := registeredSession(sessions, "R", conn)

	newTestMonitor(sessions, listener).doCheck()
	// 超时路径已条件移除；模拟随后的连接退出清理（handleConn defer 的等价逻辑）
	if sessions.RemoveIfPresent("R", session) {
		if !session.timedOut.Load() {
			notifyDisconnect(listener, session, "connection_lost")
		}
	}

	if got := listener.disconnectCount("R"); got != 1 {
		t.Fatalf("timeout + connection close must produce exactly one event, got %d", got)
	}
}

// 正常连接退出（未超时）应通知一次 connection_lost
func TestNormalConnectionCloseNotifiesConnectionLost(t *testing.T) {
	sessions := NewSessionManager()
	listener := newRecordingListener(goodToken)

	conn := newMockConn()
	session := registeredSession(sessions, "R", conn)
	// 保持心跳新鲜，不触发超时
	session.lastHeartbeatTime.Store(time.Now().UnixMilli())

	// 模拟 master.handleConn 的连接退出清理
	if sessions.RemoveIfPresent("R", session) {
		if !session.timedOut.Load() {
			notifyDisconnect(listener, session, "connection_lost")
		}
	}

	events := listener.disconnectsFor("R")
	if len(events) != 1 || events[0].reason != "connection_lost" {
		t.Fatalf("expected exactly one connection_lost event, got %+v", events)
	}
	if _, ok := sessions.Get("R"); ok {
		t.Fatalf("session should be removed on connection close")
	}
}

// 旧连接退出且已被接管：不得误报离线
func TestSupersededConnectionCloseDoesNotNotify(t *testing.T) {
	sessions := NewSessionManager()
	listener := newRecordingListener(goodToken)

	staleConn := newMockConn()
	stale := registeredSession(sessions, "R", staleConn)
	fresh := registeredSession(sessions, "R", newMockConn())

	// 旧连接退出：条件移除失败 → 不通知
	if sessions.RemoveIfPresent("R", stale) {
		notifyDisconnect(listener, stale, "connection_lost")
	}

	if listener.disconnectCount("R") != 0 {
		t.Fatalf("superseded connection close must not report R offline")
	}
	if current, ok := sessions.Get("R"); !ok || current != fresh {
		t.Fatalf("fresh session must stay registered")
	}
}

// supersededDuringScan 模拟「doCheck 取到含旧会话的快照后，注册表已被新会话接管」：
// 快照返回过期旧会话，但注册表实际已指向新会话，因此条件移除必然失败。
type supersededDuringScan struct {
	manager *SessionManager
	stale   *RunnerSession
	fresh   *RunnerSession
}

func (s *supersededDuringScan) AllSessions() []*RunnerSession {
	s.manager.Register(s.fresh)
	return []*RunnerSession{s.stale}
}

func (s *supersededDuringScan) RemoveIfPresent(runnerId string, expected *RunnerSession) bool {
	return s.manager.RemoveIfPresent(runnerId, expected)
}

// 扫描期间被接管的过期会话：只清理旧连接，绝不通知（新节点保持在线）
func TestExpiredSessionSupersededDuringScanIsNotNotified(t *testing.T) {
	sessions := NewSessionManager()
	listener := newRecordingListener(goodToken)

	staleConn := newMockConn()
	stale := NewRunnerSession("R", staleConn, "host", "127.0.0.1", "test")
	ageHeartbeat(stale, time.Hour)
	sessions.Register(stale)

	freshConn := newMockConn()
	fresh := NewRunnerSession("R", freshConn, "host", "127.0.0.1", "test")

	source := &supersededDuringScan{manager: sessions, stale: stale, fresh: fresh}
	NewHeartbeatMonitor(source, listener, time.Second, 5*time.Second, testLogger()).doCheck()

	if got := listener.disconnectCount("R"); got != 0 {
		t.Fatalf("session superseded during scan must not be reported offline, got %d events", got)
	}
	if !stale.timedOut.Load() {
		t.Fatalf("stale session should still be marked timed out")
	}
	if !staleConn.isClosed() {
		t.Fatalf("stale connection should still be closed")
	}
	if current, ok := sessions.Get("R"); !ok || current != fresh {
		t.Fatalf("fresh session must stay registered")
	}
	if freshConn.isClosed() {
		t.Fatalf("fresh connection must stay open")
	}
}

// interleavingSource 在监控器执行条件移除之前，插入「连接退出路径抢先移除」的可控同步点：
// 用真实 NexaMaster.cleanupConnection 复现「标记超时 → 连接退出移除 → 监控器移除失败」的时序。
type interleavingSource struct {
	master  *NexaMaster
	session *RunnerSession
	connCtx *ConnContext
	fired   bool
}

func (s *interleavingSource) AllSessions() []*RunnerSession {
	return []*RunnerSession{s.session}
}

func (s *interleavingSource) RemoveIfPresent(runnerId string, expected *RunnerSession) bool {
	if !s.fired {
		s.fired = true
		s.master.cleanupConnection(s.connCtx) // 连接退出路径抢先移除并通知
	}
	return s.master.sessions.RemoveIfPresent(runnerId, expected)
}

// D.1 漏通知竞态：监控器标记超时后，连接退出路径抢先完成条件移除。
// 事件所有权规则下必须恰好通知一次；若连接退出路径凭 timedOut 标记推断监控器已通知而跳过，
// 双方都不通知 → 掉线事件漏发。
func TestTimeoutAndConnectionCloseInterleavingNotifiesOnce(t *testing.T) {
	sessions := NewSessionManager()
	listener := newRecordingListener(goodToken)
	master := &NexaMaster{sessions: sessions, listener: listener, logger: testLogger()}

	conn := newMockConn()
	session := NewRunnerSession("R", conn, "host", "127.0.0.1", "test")
	ageHeartbeat(session, time.Hour) // 会话已过期
	sessions.Register(session)

	connCtx := &ConnContext{Session: session}
	source := &interleavingSource{master: master, session: session, connCtx: connCtx}

	// 真实监控器：doCheck 先 MarkTimedOut，再调用 source.RemoveIfPresent 触发交错
	NewHeartbeatMonitor(source, listener, time.Second, 5*time.Second, testLogger()).doCheck()

	events := listener.disconnectsFor("R")
	if len(events) != 1 {
		t.Fatalf("timeout/close interleaving must produce exactly one event, got %+v", events)
	}
	if events[0].reason != "heartbeat_timeout" {
		t.Fatalf("winning remover should report heartbeat_timeout, got %q", events[0].reason)
	}
	if events[0].session != session {
		t.Fatalf("event must carry the removed session identity")
	}
	if _, ok := sessions.Get("R"); ok {
		t.Fatalf("session should be removed exactly once")
	}
	if !session.timedOut.Load() {
		t.Fatalf("session should stay marked as timed out")
	}
}

// 旧 API 兼容：未实现 SessionDisconnectListener 的 listener 仍可收到断开事件
func TestLegacyListenerReceivesDisconnect(t *testing.T) {
	sessions := NewSessionManager()

	legacy := &legacyListener{}
	registeredSession(sessions, "R", newMockConn())

	newTestMonitor(sessions, legacy).doCheck()

	if len(legacy.events) != 1 || legacy.events[0] != "R:heartbeat_timeout" {
		t.Fatalf("legacy OnDisconnect should still be invoked, got %+v", legacy.events)
	}
}

// legacyListener 只实现基础 Listener 接口（不实现 SessionDisconnectListener）
type legacyListener struct {
	events []string
}

func (l *legacyListener) OnRegister(session *RunnerSession, req *messages.RegisterRequest) *messages.RegisterResponse {
	return &messages.RegisterResponse{Success: true, Message: "ok"}
}

func (l *legacyListener) OnHeartbeat(session *RunnerSession, req *messages.HeartbeatRequest) {}

func (l *legacyListener) OnDisconnect(runnerId, reason string) {
	l.events = append(l.events, runnerId+":"+reason)
}

// 并发交错：超时扫描与新连接接管同时发生。
// 不变量：通知不超过 1 次，且只可能以旧会话身份通知，新会话绝不被标记掉线。
func TestConcurrentTakeoverDuringTimeoutScan(t *testing.T) {
	for round := 0; round < 200; round++ {
		sessions := NewSessionManager()
		listener := newRecordingListener(goodToken)

		staleConn := newMockConn()
		stale := registeredSession(sessions, "R", staleConn)
		ageHeartbeat(stale, time.Hour) // 旧会话过期

		freshConn := newMockConn()
		fresh := NewRunnerSession("R", freshConn, "host", "127.0.0.1", "test")
		// 新会话心跳新鲜：在 1 秒超时下不会过期

		monitor := NewHeartbeatMonitor(sessions, listener, time.Second, 5*time.Second, testLogger())

		start := make(chan struct{})
		done := make(chan struct{}, 2)

		go func() {
			<-start
			monitor.doCheck()
			done <- struct{}{}
		}()
		go func() {
			<-start
			sessions.Register(fresh)
			done <- struct{}{}
		}()

		close(start)
		for i := 0; i < 2; i++ {
			select {
			case <-done:
			case <-time.After(5 * time.Second):
				t.Fatalf("round %d did not finish in time", round)
			}
		}

		events := listener.disconnectsFor("R")
		if len(events) > 1 {
			t.Fatalf("round %d: at most one disconnect event expected, got %d", round, len(events))
		}
		for _, event := range events {
			if event.session == fresh {
				t.Fatalf("round %d: fresh session must never be reported as disconnected", round)
			}
			if event.session != stale {
				t.Fatalf("round %d: only the stale session may be reported", round)
			}
		}
	}
}
