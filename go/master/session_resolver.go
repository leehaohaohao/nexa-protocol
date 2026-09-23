package master

// resolveSession 解析发送方当前会话（Go 版统一入口，与 Java SessionResolver 对齐）。
//
// 规则：
//   - 连接必须已注册：connCtx.Session 存在且 runnerId 非空
//   - 该会话必须仍是注册表中的当前会话：已被同 ID 新连接接管的旧连接解析失败
//
// 报文中的 runnerId / Envelope.source_id 都是客户端提供的数据，
// 只能用于一致性检查（见 matchesRunnerId），不能用于选择会话。
func resolveSession(sessions *SessionManager, connCtx *ConnContext) (*RunnerSession, bool) {
	if connCtx == nil || connCtx.Session == nil || connCtx.Session.RunnerId == "" {
		return nil, false
	}
	if !sessions.IsCurrent(connCtx.Session) {
		return nil, false
	}
	return connCtx.Session, true
}

// matchesRunnerId 报文声明的 runnerId 是否与会话身份一致；声明为空表示未声明，跳过检查
func matchesRunnerId(session *RunnerSession, declared string) bool {
	return declared == "" || declared == session.RunnerId
}
