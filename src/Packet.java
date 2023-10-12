package src;

import java.util.ArrayList;

public class Packet {
    public byte length;
    public Payload payload;
    public byte src8;

    public Packet() {
    }

    public static long fromULEB(byte[] values, Counter counter) {
        long result = 0;
        int shift = 0;
        while (counter.value < values.length) {
            result |= ((long) values[counter.value] & 127) << shift;
            counter.value++;
            if ((values[counter.value - 1] >> 7 & 1) == 0) break;
            shift += 7;
        }
        return result;
    }

    public static void toULEB(int value, ArrayList<Byte> bytes) {
        byte b;
        do {
            b = (byte) (value & 127);
            value >>= 7;
            if (value != 0) b |= (1 << 7);
            bytes.add(b);
        } while (value != 0);
    }
}
