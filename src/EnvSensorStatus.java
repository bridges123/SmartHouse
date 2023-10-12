package src;

public class EnvSensorStatus {
    public int[] values;

    public EnvSensorStatus(byte[] data, Counter counter) {
        int len = data[counter.value++];
        values = new int[len];
        for (int i = 0; i < len; i++) {
            values[i] = (int) Packet.fromULEB(data, counter);
        }
    }
}
