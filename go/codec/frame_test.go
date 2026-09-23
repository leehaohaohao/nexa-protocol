package codec

import (
	"bytes"
	"encoding/binary"
	"errors"
	"io"
	"testing"
)

// shortWriter 每次最多写出 max 字节，用于模拟底层 Writer 短写
type shortWriter struct {
	buf bytes.Buffer
	max int
}

func (w *shortWriter) Write(p []byte) (int, error) {
	if len(p) > w.max {
		p = p[:w.max]
	}
	return w.buf.Write(p)
}

// TestWriteFrameHandlesShortWrites 底层短写时也必须写出完整帧
func TestWriteFrameHandlesShortWrites(t *testing.T) {
	payload := []byte("hello-nexa-protocol")

	w := &shortWriter{max: 3} // 每次只写 3 字节，必然触发短写
	if err := WriteFrame(w, payload); err != nil {
		t.Fatalf("WriteFrame: %v", err)
	}

	got := w.buf.Bytes()
	wantLen := headerSize + len(payload)
	if len(got) != wantLen {
		t.Fatalf("expected %d bytes written, got %d", wantLen, len(got))
	}

	if length := binary.BigEndian.Uint32(got[:headerSize]); int(length) != len(payload) {
		t.Fatalf("expected length header %d, got %d", len(payload), length)
	}
	if !bytes.Equal(got[headerSize:], payload) {
		t.Fatalf("payload mismatch: %q", got[headerSize:])
	}
}

// zeroWriter 永远返回 (0, nil)，用于验证 WriteFrame 不会死循环
type zeroWriter struct{}

func (w *zeroWriter) Write(p []byte) (int, error) {
	return 0, nil
}

// TestWriteFrameZeroWriteReturnsError 写入方返回 0 字节且无错误时应中止而非死循环
func TestWriteFrameZeroWriteReturnsError(t *testing.T) {
	err := WriteFrame(&zeroWriter{}, []byte("data"))
	if !errors.Is(err, io.ErrShortWrite) {
		t.Fatalf("expected io.ErrShortWrite, got %v", err)
	}
}

// TestFrameRoundTrip 写入后能读回同样的数据
func TestFrameRoundTrip(t *testing.T) {
	payload := []byte("round-trip-payload")

	var buf bytes.Buffer
	if err := WriteFrame(&buf, payload); err != nil {
		t.Fatalf("WriteFrame: %v", err)
	}

	got, err := ReadFrame(&buf)
	if err != nil {
		t.Fatalf("ReadFrame: %v", err)
	}
	if !bytes.Equal(got, payload) {
		t.Fatalf("round trip mismatch: %q", got)
	}
}

// TestReadFrameRejectsOversize 超过帧上限时拒绝读取
func TestReadFrameRejectsOversize(t *testing.T) {
	header := make([]byte, headerSize)
	binary.BigEndian.PutUint32(header, 10*1024*1024+1)

	if _, err := ReadFrame(bytes.NewReader(header)); err == nil {
		t.Fatal("expected error for oversize frame, got nil")
	}
}
