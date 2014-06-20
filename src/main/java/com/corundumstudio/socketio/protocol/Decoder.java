/**
 * Copyright 2012 Nikita Koksharov
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.corundumstudio.socketio.protocol;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufInputStream;
import io.netty.buffer.ByteBufProcessor;
import io.netty.buffer.Unpooled;
import io.netty.util.CharsetUtil;

import java.io.IOException;
import java.util.UUID;

import com.corundumstudio.socketio.AckCallback;
import com.corundumstudio.socketio.ack.AckManager;
import com.corundumstudio.socketio.namespace.Namespace;

public class Decoder {

    private final JsonSupport jsonSupport;
    private final AckManager ackManager;

    public Decoder(JsonSupport jsonSupport, AckManager ackManager) {
        this.jsonSupport = jsonSupport;
        this.ackManager = ackManager;
    }

    // fastest way to parse chars to int
    private long parseLong(ByteBuf chars) {
        return parseLong(chars, chars.readerIndex() + chars.readableBytes());
    }

    private long parseLong(ByteBuf chars, int length) {
        long result = 0;
        for (int i = chars.readerIndex(); i < length; i++) {
            int digit = ((int)chars.getByte(i) & 0xF);
            for (int j = 0; j < length-1-i; j++) {
                digit *= 10;
            }
            result += digit;
        }
        return result;
    }

    private Packet decodePacket(ByteBuf buffer, UUID uuid) throws IOException {
        if (buffer.readableBytes() < 3) {
            throw new DecoderException("Can't parse " + buffer.toString(CharsetUtil.UTF_8));
        }
        PacketType type = getType(buffer);

        int readerIndex = buffer.readerIndex()+1;
        // 'null' to avoid unnecessary StringBuilder creation
        boolean hasData = false;
        StringBuilder messageId = null;
        for (readerIndex += 1; readerIndex < buffer.readableBytes(); readerIndex++) {
            if (messageId == null) {
                messageId = new StringBuilder(4);
            }
            byte msg = buffer.getByte(readerIndex);
//            if (msg == Packet.SEPARATOR) {
//                break;
//            }
            if (msg != (byte)'+') {
                messageId.append((char)msg);
            } else {
                hasData = true;
            }
        }
        Long id = null;
        if (messageId != null && messageId.length() > 0) {
            id = Long.valueOf(messageId.toString());
        }

        // 'null' to avoid unnecessary StringBuilder creation
        StringBuilder endpointBuffer = null;
        for (readerIndex += 1; readerIndex < buffer.readableBytes(); readerIndex++) {
            if (endpointBuffer == null) {
                endpointBuffer = new StringBuilder();
            }
            byte msg = buffer.getByte(readerIndex);
//            if (msg == Packet.SEPARATOR) {
//                break;
//            }
            endpointBuffer.append((char)msg);
        }

        String endpoint = Namespace.DEFAULT_NAME;
        if (endpointBuffer != null && endpointBuffer.length() > 0) {
            endpoint = endpointBuffer.toString();
        }

        if (buffer.readableBytes() == readerIndex) {
            buffer.readerIndex(buffer.readableBytes());
        } else {
            readerIndex += 1;
            buffer.readerIndex(readerIndex);
        }

        Packet packet = new Packet(type);
        packet.setNsp(endpoint);

        switch (type) {
        case ERROR: {
            if (!buffer.isReadable()) {
                break;
            }
            String[] pieces = buffer.toString(CharsetUtil.UTF_8).split("\\+");
            if (pieces.length > 0 && pieces[0].trim().length() > 0) {
                ErrorReason reason = ErrorReason.valueOf(Integer.valueOf(pieces[0]));
                packet.setReason(reason);
                if (pieces.length > 1) {
                    ErrorAdvice advice = ErrorAdvice.valueOf(Integer.valueOf(pieces[1]));
                    packet.setAdvice(advice);
                }
            }
            break;
        }

        case EVENT: {
            ByteBufInputStream in = new ByteBufInputStream(buffer);
            Event event = jsonSupport.readValue(in, Event.class);
            packet.setName(event.getName());
            if (event.getArgs() != null) {
//                packet.setArgs(event.getArgs());
            }
            break;
        }

        case ACK: {
            if (!buffer.isReadable()) {
                break;
            }
            boolean validFormat = true;
            int plusIndex = -1;
            for (int i = buffer.readerIndex(); i < buffer.readerIndex() + buffer.readableBytes(); i++) {
                byte dataChar = buffer.getByte(i);
                if (!Character.isDigit(dataChar)) {
                    if (dataChar == '+') {
                        plusIndex = i;
                        break;
                    } else {
                        validFormat = false;
                        break;
                    }
                }
            }
            if (!validFormat) {
                break;
            }

            if (plusIndex == -1) {
                packet.setAckId(parseLong(buffer));
                break;
            } else {
                packet.setAckId(parseLong(buffer, plusIndex));
                buffer.readerIndex(plusIndex+1);

                ByteBufInputStream in = new ByteBufInputStream(buffer);
                AckCallback<?> callback = ackManager.getCallback(uuid, packet.getAckId());
                AckArgs args = jsonSupport.readAckArgs(in, callback);
//                packet.setArgs(args.getArgs());
            }
            break;
        }

        case DISCONNECT:
            break;
        }

        buffer.readerIndex(buffer.readerIndex() + buffer.readableBytes());
        return packet;
    }

    private PacketType getType(ByteBuf buffer) {
        return null;
//        int typeId = buffer.getByte(buffer.readerIndex()) & 0xF;
//        if (typeId >= PacketType.VALUES.length
//                || buffer.getByte(buffer.readerIndex()+1) != Packet.SEPARATOR) {
//            throw new DecoderException("Can't parse " + buffer.toString(CharsetUtil.UTF_8));
//        }
//        return PacketType.valueOf(typeId);
    }

    public Packet decodePacket(String string, UUID uuid) throws IOException {
        ByteBuf buf = Unpooled.copiedBuffer(string, CharsetUtil.UTF_8);
        try {
            Packet packet = decodePacket(buf, uuid);
            return packet;
        } finally {
            buf.release();
        }
    }

    public Packet decodePackets(ByteBuf buffer, UUID uuid) throws IOException {
        Packet packet;
//        int startIndex = buffer.readerIndex();
        boolean isString = buffer.getByte(0) == 0x0;
        if (isString) {
            int headEndIndex = buffer.forEachByte(new ByteBufProcessor() {
                @Override
                public boolean process(byte value) throws Exception {
                    return value != -1;
                }
            });
            int len = (int) parseLong(buffer, headEndIndex);

            buffer.readerIndex(buffer.readerIndex() + headEndIndex);

            ByteBuf frame = buffer.slice(buffer.readerIndex()+1, len);
            packet = decode(uuid, frame);
            buffer.readerIndex(buffer.readerIndex() + len + 1);
        } else {
//            ByteBuf frame = buffer.slice(buffer.readerIndex()+1, len);
            String msg = buffer.toString(CharsetUtil.UTF_8);
            packet = decode(uuid, buffer);
            buffer.readerIndex(buffer.readerIndex() + msg.length());
        }
        return packet;
    }

    private Packet decode(UUID uuid, ByteBuf frame) throws IOException {
        Packet packet;
        String msg = frame.toString(CharsetUtil.UTF_8);
        PacketType type = PacketType.valueOf(Integer.valueOf(String.valueOf(msg.charAt(0))));
        packet = new Packet(type);
        // skip type
        msg = msg.substring(1);

        if (type == PacketType.PING) {
            packet.setData(msg);
            return packet;
        }

        if (msg.length() >= 1) {
            PacketType innerType = PacketType.valueOfInner(Integer.valueOf(String.valueOf(msg.charAt(0))));
            packet.setSubType(innerType);
            // skip inner type
            msg = msg.substring(1);

            int endIndex = msg.indexOf("[");
            if (endIndex > 0) {
                String nspAckId = msg.substring(0, endIndex);
                if (nspAckId.contains(",")) {
                    String[] parts = nspAckId.split(",");
                    String nsp = parts[0];
                    packet.setNsp(nsp);
                    if (parts.length > 1) {
                        String ackId = parts[1];
                        packet.setAckId(Long.valueOf(ackId));
                    }
                } else {
                    packet.setAckId(Long.valueOf(nspAckId));
                }
                // skip 'nsp,ackId' part
                msg = msg.substring(endIndex);
            }

            if (packet.getType() == PacketType.MESSAGE) {
                if (packet.getSubType() == PacketType.CONNECT) {
                    packet.setNsp(msg);
                }

                if (packet.getSubType() == PacketType.ACK) {
                    ByteBufInputStream in = new ByteBufInputStream(Unpooled.copiedBuffer(msg, CharsetUtil.UTF_8));
                    AckCallback<?> callback = ackManager.getCallback(uuid, packet.getAckId());
                    AckArgs args = jsonSupport.readAckArgs(in, callback);
                    packet.setData(args.getArgs());
                }

                if (packet.getSubType() == PacketType.EVENT) {
                    Event event = jsonSupport.readValue(msg, Event.class);
                    packet.setName(event.getName());
                    packet.setData(event.getArgs());
                }
            }
        }
        return packet;
    }

}