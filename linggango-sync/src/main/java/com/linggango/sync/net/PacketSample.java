package com.linggango.sync.net;

/**
 * One observed packet. Kept small and immutable so the sample ring buffer stays cheap.
 *
 * @param timestampMillis when the packet was observed
 * @param direction       which way it travelled
 * @param kind            protocol phase
 * @param mod             attributed mod display name
 * @param packetType      simple class name, or channel name for custom payloads
 * @param channel         custom-payload channel, or {@code null}
 * @param bytes           encoded size in bytes
 * @param player          player name/handle the packet was associated with, or {@code null}
 */
public record PacketSample(long timestampMillis,
                           PacketDirection direction,
                           PacketKind kind,
                           String mod,
                           String packetType,
                           String channel,
                           int bytes,
                           String player) {

    public String describe() {
        StringBuilder sb = new StringBuilder();
        sb.append(direction.label()).append(' ')
                .append(ByteFormat.bytes(bytes)).append(' ')
                .append(mod).append(" / ").append(packetType);
        if (channel != null) {
            sb.append(" [").append(channel).append(']');
        }
        if (player != null) {
            sb.append(" (").append(player).append(')');
        }
        return sb.toString();
    }
}
