package nhcm.jvmrtdp.protocol;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.util.zip.CRC32;

public class ChannelFrameCodec {
    private static final int HEADER_BYTES = 24;

    public Frame read(ReadableByteChannel channel) throws IOException {
        ByteBuffer header = ByteBuffer.allocate(HEADER_BYTES);
        readFully(channel, header);
        header.flip();

        int magic = header.getInt();
        if (magic != Protocol.MAGIC) {
            throw new IOException("Invalid JVMRTDP frame magic: 0x" + Integer.toHexString(magic));
        }
        short version = header.getShort();
        if (version != Protocol.VERSION) {
            throw new IOException("Unsupported JVMRTDP protocol version: " + version);
        }
        MessageType type = MessageType.fromCode(Byte.toUnsignedInt(header.get()));
        header.get(); // reserved flags
        long requestId = header.getLong();
        int payloadLength = header.getInt();
        int expectedChecksum = header.getInt();
        if (payloadLength < 0 || payloadLength > Protocol.MAX_PAYLOAD_BYTES) {
            throw new IOException("Invalid JVMRTDP payload length: " + payloadLength);
        }

        ByteBuffer payloadBuffer = ByteBuffer.allocate(payloadLength);
        readFully(channel, payloadBuffer);
        byte[] payload = payloadBuffer.array();
        if (checksum(payload) != Integer.toUnsignedLong(expectedChecksum)) {
            throw new IOException("JVMRTDP payload checksum mismatch");
        }
        return new Frame(type, requestId, payload);
    }

    public void write(WritableByteChannel channel, Frame frame) throws IOException {
        byte[] payload = frame.payload();
        ByteBuffer buffer = ByteBuffer.allocate(HEADER_BYTES + payload.length);
        buffer.putInt(Protocol.MAGIC);
        buffer.putShort(Protocol.VERSION);
        buffer.put((byte) frame.type().code());
        buffer.put((byte) 0);
        buffer.putLong(frame.requestId());
        buffer.putInt(payload.length);
        buffer.putInt((int) checksum(payload));
        buffer.put(payload);
        buffer.flip();
        while (buffer.hasRemaining()) {
            channel.write(buffer);
        }
    }

    private static void readFully(ReadableByteChannel channel, ByteBuffer buffer) throws IOException {
        while (buffer.hasRemaining()) {
            int read = channel.read(buffer);
            if (read < 0) {
                throw new EOFException("JVMRTDP peer closed the connection");
            }
            if (read == 0) {
                Thread.yield();
            }
        }
    }

    private static long checksum(byte[] payload) {
        CRC32 crc32 = new CRC32();
        crc32.update(payload);
        return crc32.getValue();
    }
}
