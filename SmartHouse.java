import java.io.*;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLConnection;
import java.util.*;

public class SmartHouse {
    static long time = 0, serial = 1;
    static int hub = 0;
    static URL url = null;
    static String deviceName = "SmartHub";
    static ArrayList<Device> devices = new ArrayList<>();

    public static void main(String[] args) {
        hub = Integer.parseInt(args[1].trim(), 16);
        String raw;
        try {
            url = new URL(args[0]);
        } catch (MalformedURLException e) {
            System.exit(99);
        }

        raw = sendDataRequest(whoIsHereData());
        processReturnData(raw);
        Optional<Device> optSwitch = devices.stream().filter(d -> d.type == 3).findFirst();
        if (optSwitch.isPresent()) {
            raw = sendDataRequest(getStatusData(optSwitch.get().address));
            System.out.println(raw);
            processReturnData(raw);
        }
    }

    public static String sendDataRequest(String data) {
        System.out.println("reqData " + data);
        String raw = requestServer(data);
        if (Objects.equals(raw, "")) System.exit(99);

        raw = requestServer(data);
        if (Objects.equals(raw, "")) System.exit(99);
        return raw;
    }

    public static void processReturnData(String raw) {
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
                    if (packet.payload.cmd == 6) time = packet.payload.cmdBody.timestamp;
                } else {
                    int index = 0;
                    Device device = null;

                    Optional<Device> optDevice = devices.stream().filter(d -> d.address == packet.payload.src).findFirst();
                    if (optDevice.isPresent()) {
                        device = optDevice.get();
                        index = devices.indexOf(device);
                    } else {
                        device = new Device(packet.payload.src, packet.payload.devType);
                        if (packet.payload.cmd == 1 || packet.payload.cmd == 2)
                            device.name = packet.payload.cmdBody.devName;
                        devices.add(device);
                        System.out.println("added " + device);
                        index = devices.size() - 1;
                    }

                    if (packet.payload.devType == 3) {
                        if (packet.payload.cmdBody.connectedSwitch != null)
                            device.connectedSwitch = packet.payload.cmdBody.connectedSwitch;
                        if (packet.payload.cmd == 4) {
                            device.active = (packet.payload.cmdBody.status == 1);
                            System.out.println(packet);
                            if (device.active)
                                switchTurnConnectedDevices(device.connectedSwitch);
                        }
                    }
                    device.lastTimeUpdate = time;
                    devices.set(index, device);
                    System.out.println(packet);
                }

                for (Device device : devices) {
                    if (time - device.lastTimeUpdate > 300)
                        device.active = false;
                    else
                        System.out.println("device: " + device);
                }
                System.out.println("end devices");
            }
        } catch (IllegalArgumentException ignored) {
        }
    }

    public static void switchTurnConnectedDevices(String[] devicesNames) {
        for (String deviceName : devicesNames) {
            Optional<Device> optDevice = devices.stream().filter(d -> d.name.equals(deviceName)).findFirst();
            if (optDevice.isPresent()) {
                Device device = optDevice.get();
                System.out.println("switched on " + device);
                int index = devices.indexOf(device);
                device.active = true;
                devices.set(index, device);

                String raw = requestServer(setStatusData(device.address, (byte) 1));
                System.out.println(raw);
                processReturnData(raw);
            }
        }
    }

    public static String requestServer(String data) {
        String raw = "";
        try {
            URLConnection connection = url.openConnection();
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
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
            System.exit(99);
        }
        return raw;
    }

    public static String whoIsHereData() {
        ArrayList<Byte> payload = new ArrayList<>();
        Packet.toULEB(hub, payload);
        Packet.toULEB(0x3FFF, payload);
        Packet.toULEB((int) serial, payload);
        payload.add((byte) 1);
        payload.add((byte) 1);
        payload.add((byte) deviceName.length());
        for (byte b : deviceName.getBytes())
            payload.add(b);

        byte[] bytes = listToBytes(payload);
        bytes[bytes.length - 1] = computeSRC8(payload);
        System.out.println("bytes " + Arrays.toString(bytes));

        return Base64.getEncoder().encodeToString(bytes).replace("=", "").replace('+', '-').replace('/', '_');
    }

    public static byte[] listToBytes(ArrayList<Byte> byteList) {
        byte[] bytes = new byte[1 + byteList.size() + 1];
        bytes[0] = (byte) byteList.size();
        for (int i = 0; i < byteList.size(); i++)
            bytes[i + 1] = byteList.get(i);
        return bytes;
    }

    public static String getStatusData(int dst) {
        ArrayList<Byte> payload = new ArrayList<>();
        Packet.toULEB(hub, payload);
        Packet.toULEB(dst, payload);
        Packet.toULEB((int) serial++, payload);
        payload.add((byte) 1); // devType
        payload.add((byte) 3); // cmd

        byte[] bytes = listToBytes(payload);
        bytes[bytes.length - 1] = computeSRC8(payload);
        System.out.println("bytes " + Arrays.toString(bytes));
        return Base64.getEncoder().encodeToString(bytes).replace("=", "").replace('+', '-').replace('/', '_');
    }

    public static String setStatusData(int dst, byte status) {
        ArrayList<Byte> payload = new ArrayList<>();
        Packet.toULEB(hub, payload);
        Packet.toULEB(dst, payload);
        Packet.toULEB((int) serial++, payload);
        payload.add((byte) 1); // devType
        payload.add((byte) 5); // cmd
        payload.add(status); // cmdBody

        byte[] bytes = listToBytes(payload);
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
        this.cmdBody = new CmdBody();
        switch (this.cmd) {
            case 2 -> {
                this.cmdBody.devName = decodeString(data, counter);
                switch (this.devType) {
                    case 3 -> {
                        this.cmdBody.connectedSwitch = new String[data[counter.value++]];
                        for (int i = 0; i < this.cmdBody.connectedSwitch.length; i++) {
                            this.cmdBody.connectedSwitch[i] = decodeString(data, counter);
                        }
                    }
                }
            }
            case 4 -> {
                switch (this.devType) {
                    case 3 -> {
                        this.cmdBody.status = data[counter.value++];
                    }
                }
            }
            case 6 -> this.cmdBody.timestamp = Packet.fromULEB(data, counter);
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
    public String devName;
    public byte[] devProps;
    public long timestamp;
    public byte status;
    public String[] connectedSwitch;

    public CmdBody() {
    }

    public CmdBody(long timestamp) {
        this.timestamp = timestamp;
    }

    public CmdBody(String devName, byte[] devProps) {
        this.devName = devName;
        this.devProps = devProps;
    }

    public CmdBody(String devName) {
        this.devName = devName;
    }

    @Override
    public String toString() {
        return "CmdBody{" +
                "devName='" + devName + '\'' +
                ", devProps=" + Arrays.toString(devProps) +
                ", timestamp=" + timestamp +
                ", connectedSwitch=" + Arrays.toString(connectedSwitch) +
                ", status=" + status +
                '}';
    }
}

class Device {
    public int address;
    public String name;
    public int type;
    public long lastTimeUpdate;
    public boolean active = true;
    public String[] connectedSwitch;

    public Device() {
    }

    public Device(int address, int type) {
        this.address = address;
        this.type = type;
    }

    @Override
    public String toString() {
        return "Device{" +
                "address=" + address +
                ", name='" + name + '\'' +
                ", type=" + type +
                ", lastTimeUpdate=" + lastTimeUpdate +
                ", active=" + active +
                ", connectedSwitch=" + Arrays.toString(connectedSwitch) +
                '}';
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