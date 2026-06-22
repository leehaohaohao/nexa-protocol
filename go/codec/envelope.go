package codec

import (
	"github.com/google/uuid"
	"github.com/leehaohaohao/nexa-protocol/go/messages"
	"google.golang.org/protobuf/proto"
	"time"
)

// BuildEnvelope 构建通用 Envelope
func BuildEnvelope(msgType messages.MessageType, payload []byte, sourceId, targetId string) *messages.Envelope {
	return &messages.Envelope{
		Version:   1,
		Type:      msgType,
		RequestId: uuid.New().String(),
		SourceId:  sourceId,
		TargetId:  targetId,
		Timestamp: time.Now().UnixMilli(),
		Payload:   payload,
	}
}

// MarshalEnvelope 序列化 Envelope 为字节
func MarshalEnvelope(env *messages.Envelope) ([]byte, error) {
	return proto.Marshal(env)
}

// UnmarshalEnvelope 反序列化字节为 Envelope
func UnmarshalEnvelope(data []byte) (*messages.Envelope, error) {
	env := &messages.Envelope{}
	if err := proto.Unmarshal(data, env); err != nil {
		return nil, err
	}
	return env, nil
}

// ExtractPayload 从 Envelope 中提取 payload
func ExtractPayload(env *messages.Envelope) []byte {
	return env.GetPayload()
}

// UnmarshalMessage 反序列化 Envelope payload 为具体消息
func UnmarshalMessage(data []byte, msg proto.Message) error {
	return proto.Unmarshal(data, msg)
}

// BuildRegisterRequest 构建注册请求
func BuildRegisterRequest(runnerId, hostname, ip, version string) *messages.Envelope {
	req := &messages.RegisterRequest{
		RunnerId: runnerId,
		Hostname: hostname,
		Ip:       ip,
		Version:  version,
	}
	payload, _ := proto.Marshal(req)
	return BuildEnvelope(messages.MessageType_REGISTER_REQ, payload, runnerId, "")
}

// BuildHeartbeatRequest 构建心跳请求
func BuildHeartbeatRequest(runnerId string, runningTasks int32, cpuUsage, memoryUsage float64) *messages.Envelope {
	req := &messages.HeartbeatRequest{
		RunnerId:     runnerId,
		RunningTasks: runningTasks,
		CpuUsage:     cpuUsage,
		MemoryUsage:  memoryUsage,
	}
	payload, _ := proto.Marshal(req)
	return BuildEnvelope(messages.MessageType_HEARTBEAT_REQ, payload, runnerId, "")
}

// BuildDisconnectRequest 构建断开请求
func BuildDisconnectRequest(runnerId, reason string) *messages.Envelope {
	req := &messages.DisconnectRequest{
		RunnerId: runnerId,
		Reason:   reason,
	}
	payload, _ := proto.Marshal(req)
	return BuildEnvelope(messages.MessageType_DISCONNECT_REQ, payload, runnerId, "")
}
