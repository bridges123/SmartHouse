package tinkoff.SmartHouseProject;

import java.io.*;
import java.net.URL;
import java.net.URLConnection;
import java.util.*;

public class SmartHouse {
    public static void main(String[] args) {
        int hub = Integer.parseInt(args[1].trim(), 16), serial = 1;
        long time = 0;
        ArrayList<Device> devices = new ArrayList<>();
        URLConnection connection = null;
        try {
            URL url = new URL(args[0]);
            connection = url.openConnection();
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        } catch (Exception ex) {
            System.exit(99);
        }
//        String data = "";
        String data = collectRequestData(hub, serial++);
        System.out.println("reqData " + data);
        String raw = requestServer(connection, data);
        if (Objects.equals(raw, "")) System.exit(99);

        System.out.println(raw);
        try {
            System.out.println(Arrays.toString(Base64.getDecoder().decode(raw)));
            byte[] bytes = Base64.getDecoder().decode(raw);
            for (int i = 0; i < bytes.length; ) {
                Packet packet = new Packet();
                packet.length = bytes[i++];
                byte[] payload = new byte[packet.length];
                for (int j = 0; j < packet.length; j++) {
                    payload[j] = bytes[i++];
                }
                packet.payload = new Payload(payload);
                packet.src8 = bytes[i++];
                if (packet.payload.devType == 6) {
                    time = packet.payload.cmdBody.timestamp;
                } else {
                    Optional<Device> optDevice = devices.stream().filter(d -> d.address == packet.payload.src).findFirst();
                    if (optDevice.isPresent()) {
                        Device device = optDevice.get();
                        int index = devices.indexOf(device);
                        device.lastTimeUpdate = time;
                        devices.set(index, device);
                    } else {
                        devices.add(new Device(packet.payload.src, packet.payload.devType));
                    }
                }
                System.out.println(packet);
            }

            for (int i = 0; i < devices.size(); i++) {
                if (time - devices.get(i).lastTimeUpdate > 300)
                    devices.remove(i);
            }
        } catch (IllegalArgumentException ignored) {
        }
    }

    public static String requestServer(URLConnection connection, String data) {
        String raw = "";
        try {
            connection.setRequestProperty("Content-Length", Integer.toString(data.length()));
            try (DataOutputStream dos = new DataOutputStream(connection.getOutputStream())) {
                dos.writeBytes(data);
            }
            raw = new BufferedReader(new InputStreamReader(connection.getInputStream())).readLine();

            if (raw.length() % 4 == 2) raw += "==";
            else if (raw.length() % 4 == 3) raw += "=";
            raw = raw.replace('-', '+').replace('_', '/');
        } catch (Exception ex) {
            ex.printStackTrace();
        }
        return raw;
    }

    public static String collectRequestData(int hub, int serial) {
        String deviceName = "SmartHub";
        ArrayList<Byte> payload = new ArrayList<>();
        Packet.toULEB(hub, payload);
        Packet.toULEB(0x3FFF, payload);
        Packet.toULEB(serial, payload);
        payload.add((byte) 1);
        payload.add((byte) 1);
        payload.add((byte) deviceName.length());
        for (byte b : deviceName.getBytes())
            payload.add(b);

        byte[] bytes = new byte[1 + payload.size() + 1];
        bytes[0] = (byte) payload.size();
        for (int i = 0; i < payload.size(); i++)
            bytes[i + 1] = payload.get(i);


        bytes[bytes.length - 1] = computeSRC8(payload);
        System.out.println("bytes " + Arrays.toString(bytes));

        return Base64.getEncoder().encodeToString(bytes).replace("=", "").replace('+', '-').replace('/', '_');
    }

    public static byte computeSRC8(ArrayList<Byte> bytes) {
        byte generator = 0x1D;
        byte crc = 0; /* start with 0 so first byte can be 'xored' in */
        for (byte currByte : bytes) {
            crc ^= currByte;
            for (int i = 0; i < 8; i++) {
                if ((crc & 0x80) != 0) {
                    crc = (byte) ((crc << 1) ^ generator);
                } else {
                    crc <<= 1;
                }
            }
        }
        return crc;
    }
}

class Packet {
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

    @Override
    public String toString() {
        return "Packet{" + "length=" + length + ", payload=" + payload + ", src8=" + src8 + '}';
    }
}

class Payload {
    public int src;
    public int dst;
    public int serial;
    public byte devType;
    public byte cmd;
    public CmdBody cmdBody;

    public Payload(byte[] data) {
        Counter counter = new Counter();
        this.src = (int) Packet.fromULEB(data, counter);
        this.dst = (int) Packet.fromULEB(data, counter);
        this.serial = (int) Packet.fromULEB(data, counter);
        this.devType = data[counter.value++];
        this.cmd = data[counter.value++];
        if (this.cmd == 6) {
            this.cmdBody = new CmdBody(Packet.fromULEB(data, counter));
        } else {
            String deviceName = decodeString(data, counter);
            this.cmdBody = new CmdBody(deviceName);
        }
    }

    public String decodeString(byte[] data, Counter counter) {
        StringBuilder builder = new StringBuilder();
        int stringLen = counter.value++;
        for (int i = 0; i < data[stringLen]; i++) {
            builder.append((char) data[counter.value++]);
        }
        return builder.toString();
    }

    @Override
    public String toString() {
        return "Payload{" + "src=" + src + ", dst=" + dst + ", serial=" + serial + ", devType=" + devType + ", cmd=" + cmd + ", cmdBody=" + cmdBody + '}';
    }
}

class CmdBody {
    public String dev_name;
    public byte[] dev_props;
    public long timestamp;

    public CmdBody(long timestamp) {
        this.timestamp = timestamp;
    }

    public CmdBody(String dev_name, byte[] dev_props) {
        this.dev_name = dev_name;
        this.dev_props = dev_props;
    }

    public CmdBody(String dev_name) {
        this.dev_name = dev_name;
    }

    @Override
    public String toString() {
        return "CmdBody{" +
                "dev_name='" + dev_name + '\'' +
                ", dev_props=" + Arrays.toString(dev_props) +
                ", timestamp=" + timestamp +
                '}';
    }
}

class Device {
    public int address;
    public int type;
    public long lastTimeUpdate;
    public long active = true;

    public Device() {
    }

    public Device(int address, int type) {
        this.address = address;
        this.type = type;
    }
}

class EnvSensorProps {
    public byte sensors;
    public Trigger[] triggers;
}

class Trigger {
    public byte op;
    public int value;
    public String name;
}

class EnvSensorStatusCmdBody {
    public int[] values;
}

class Counter {
    public int value = 0;
}