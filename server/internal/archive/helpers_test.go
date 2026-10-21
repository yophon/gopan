package archive

import "bytes"

func readerAt(b []byte) *bytes.Reader { return bytes.NewReader(b) }
