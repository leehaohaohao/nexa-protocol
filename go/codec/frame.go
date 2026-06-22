package codec

import (
	"encoding/binary"
	"errors"
	"io"
)

const headerSize = 4

// WriteFrame 写一帧：[4字节大端序长度][data]
func WriteFrame(w io.Writer, data []byte) error {
	buf := make([]byte, headerSize+len(data))
	binary.BigEndian.PutUint32(buf[:headerSize], uint32(len(data)))
	copy(buf[headerSize:], data)
	_, err := w.Write(buf)
	return err
}

// ReadFrame 读一帧：读取长度头，再读取对应长度的数据
func ReadFrame(r io.Reader) ([]byte, error) {
	header := make([]byte, headerSize)
	if _, err := io.ReadFull(r, header); err != nil {
		return nil, err
	}

	length := binary.BigEndian.Uint32(header)
	if length > 10*1024*1024 { // 10MB 上限
		return nil, errors.New("frame too large")
	}

	data := make([]byte, length)
	if _, err := io.ReadFull(r, data); err != nil {
		return nil, err
	}
	return data, nil
}
