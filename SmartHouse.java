import java.io.*;
import java.net.*;
import java.util.*;

public class SmartHouse {
    static long time = 0, serial = 1;
    static int hub = 0;
    static URL url = null;
    static String hubName = "HUB01";
    static ArrayList<Device> devices = new ArrayList<>();

    public static void main(String[] args) {
        hub = Integer.parseInt(args[1].trim(), 16);
        String raw;

        try {
            url = new URL(args[0]);
        } catch (MalformedURLException e) {
            System.exit(99);
        }

        processReturnData(sendDataRequest(whoIsHereData()));

//        raw = "OAL_fwQCAghTRU5TT1IwMQ8EDGQGT1RIRVIxD7AJBk9USEVSMgCsjQYGT1RIRVIzCAAGT1RIRVI09w";
//        if (raw.length() % 4 == 2) raw += "==";
//        else if (raw.length() % 4 == 3) raw += "=";
//        raw = raw.replace('-', '+').replace('_', '/');
//        processReturnData(raw);
//
//        raw = "EQIBBgIEBKUB4AfUjgaMjfILrw";
//        if (raw.length() % 4 == 2) raw += "==";
//        else if (raw.length() % 4 == 3) raw += "=";
//        raw = raw.replace('-', '+').replace('_', '/');
//        processReturnData(raw);

//        boolean go = true;
        while (true) {
            for (int i = 0; i < devices.size(); i++) {
                Device device = devices.get(i);
                if (device.type != 2) {
                    raw = sendDataRequest(getStatusData(device));
//                    System.out.println(raw);
                    processReturnData(raw);
//                    go = devices.stream().noneMatch(d -> (d.type == 3 && d.status == 1));
                }
            }
        }
    }

    public static String sendDataRequest(String data) {
//        System.out.println("reqData " + data);
        String raw = requestServer(data);
        if (Objects.equals(raw, "")) System.exit(99);
        return raw;
    }

    // обработка ответа
    public static void processReturnData(String raw) {
//        System.out.println(Arrays.toString(Base64.getDecoder().decode(raw)));
        byte[] bytes = Base64.getDecoder().decode(raw);
        for (int i = 0; i < bytes.length; ) {
            // заполняем пакет данными
            Packet packet = new Packet();
            packet.length = bytes[i++];
            byte[] payload = new byte[packet.length];
            for (int j = 0; j < packet.length; j++) {
                payload[j] = bytes[i++];
            }
            packet.payload = new Payload(payload);
            packet.src8 = bytes[i++];

            if (packet.payload.cmd == 6) {
                time = packet.payload.cmdBody.timestamp;
            } else if (packet.payload.devType != 6) {
                Device device;

                // если id устройства пакета есть в списке девайсов, берем индекс, иначе добавляем
                Optional<Device> optDevice = devices.stream()
                        .filter(d -> d.address == packet.payload.src)
                        .findFirst();
                if (optDevice.isPresent()) {
                    device = optDevice.get();
                } else {
                    device = new Device(packet.payload.src, packet.payload.devType);
                    if (packet.payload.cmd == 1 || packet.payload.cmd == 2)
                        device.name = packet.payload.cmdBody.devName;
                    devices.add(device);
                }

                // обрабатываем кейсы комбинаций (устройство, команда)
                if (packet.payload.cmd == 1 || packet.payload.cmd == 2) {
                    device.active = true;
                    if (packet.payload.devType == 3) {
                        if (packet.payload.cmdBody.connectedSwitch != null)
                            device.connectedSwitch = packet.payload.cmdBody.connectedSwitch;
                    }
                    if (packet.payload.devType == 2) {
                        device.sensorProps = packet.payload.cmdBody.sensorProps;
                    }
                    if (packet.payload.cmd == 1) {
                        processReturnData(sendDataRequest(IAmHereData()));
                    }
                    processReturnData(sendDataRequest(getStatusData(device)));
                }

                if (device.active) {
                    if (packet.payload.cmd == 4) {
                        if (packet.payload.devType == 2) {
                            EnvSensorStatus sensorStatus = packet.payload.cmdBody.sensorStatus;
                            EnvSensorProps sensorProps = packet.payload.cmdBody.sensorProps;
                            if (sensorStatus != null && sensorProps != null) {
                                for (int k = 0, sid = 0; k < sensorProps.sensors.length; k++) {
                                    if (sensorProps.sensors[k]) {
                                        int sensorIndex = k;
                                        List<Trigger> triggers = Arrays.stream(sensorProps.triggers)
                                                .filter(trigger -> trigger.sensorTypes == sensorIndex).toList();
                                        for (Trigger trigger : triggers) {
                                            boolean triggerResult;
                                            if (trigger.greater)
                                                triggerResult = sensorStatus.values[sid] > trigger.value;
                                            else
                                                triggerResult = sensorStatus.values[sid] < trigger.value;
                                            if (triggerResult) {
                                                Optional<Device> optDeviceToTurn = devices.stream()
                                                        .filter(d -> d.name.equals(trigger.name))
                                                        .findFirst();
                                                optDeviceToTurn.ifPresent(value -> processReturnData(
                                                        sendDataRequest(setStatusData(value, trigger.on))
                                                ));
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        if (packet.payload.devType == 3 || packet.payload.devType == 4 || packet.payload.devType == 5) {
                            device.status = packet.payload.cmdBody.status;
                            if (packet.payload.devType == 3) {
//                                System.out.println("switch " + device.name + " status cmd " + device.status);
                                if (device.active && device.connectedSwitch != null)
                                    switchTurnConnectedDevices(device);
                            }
                        }
                    }
                    device.lastTimeUpdate = time;
                }
            }
//            System.out.println(packet);
        }
//        for (Device device : devices) {
//            if (time - device.lastTimeUpdate > 300)
//                device.active = false;
//            else
//                System.out.println("device: " + device);
//        }
//        System.out.println("end devices");
    }

    // вкл/выкл подключенных к switch девайсов
    public static void switchTurnConnectedDevices(Device switchDev) {
        for (String deviceName : switchDev.connectedSwitch) {
//            System.out.println("switch devices " + devices);
            Optional<Device> optDevice = devices.stream().filter(d -> d.name.equals(deviceName)).findFirst();
            if (optDevice.isPresent()) {
                Device connDev = optDevice.get();
//                System.out.println("switched on " + connDev.name);
                connDev.status = 1;

                String raw = sendDataRequest(setStatusData(connDev, switchDev.status));
//                System.out.println(raw);
                processReturnData(raw);
            }
        }
    }

    public static String requestServer(String data) {
        String raw = "";
        try {
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            connection.setRequestProperty("Content-Length", Integer.toString(data.length()));
            try (DataOutputStream dos = new DataOutputStream(connection.getOutputStream())) {
                dos.writeBytes(data);
            }
            if (connection.getResponseCode() == 204) {
//                System.out.println("devices after " + devices);
                System.exit(0);
            } else if (connection.getResponseCode() != 200) {
                System.exit(99);
            }

            raw = new BufferedReader(new InputStreamReader(connection.getInputStream())).readLine();
            if (raw.length() % 4 == 2) raw += "==";
            else if (raw.length() % 4 == 3) raw += "=";
            raw = raw.replace('-', '+').replace('_', '/');
        } catch (Exception ex) {
//            ex.printStackTrace();
            System.exit(99);
        }
        return raw;
    }

    public static String whoIsHereData() {
        ArrayList<Byte> payload = new ArrayList<>();
        Packet.toULEB(hub, payload);
        Packet.toULEB(0x3FFF, payload);
        Packet.toULEB((int) serial++, payload);
        payload.add((byte) 1); // devType
        payload.add((byte) 1); // cmd
        payload.add((byte) hubName.length()); // cmdBody
        for (byte b : hubName.getBytes())
            payload.add(b);

        return generateRequestRaw(payload);
    }

    public static String IAmHereData() {
        ArrayList<Byte> payload = new ArrayList<>();
        Packet.toULEB(hub, payload);
        Packet.toULEB(0x3FFF, payload);
        Packet.toULEB((int) serial++, payload);
        payload.add((byte) 1); // devType
        payload.add((byte) 2); // cmd
        payload.add((byte) hubName.length()); // cmdBody
        for (byte b : hubName.getBytes())
            payload.add(b);

        return generateRequestRaw(payload);
    }

    public static String getStatusData(Device device) {
        ArrayList<Byte> payload = new ArrayList<>();
        Packet.toULEB(hub, payload);
        Packet.toULEB(device.address, payload);
        Packet.toULEB((int) serial++, payload);
        payload.add((byte) device.type); // devType
        payload.add((byte) 3); // cmd

        return generateRequestRaw(payload);
    }

    public static String setStatusData(Device device, byte status) {
        ArrayList<Byte> payload = new ArrayList<>();
        Packet.toULEB(hub, payload);
        Packet.toULEB(device.address, payload);
        Packet.toULEB((int) serial++, payload);
        payload.add((byte) device.type); // devType
        payload.add((byte) 5); // cmd
        payload.add(status); // cmdBody

        return generateRequestRaw(payload);
    }

    public static byte[] listToBytes(ArrayList<Byte> byteList) {
        byte[] bytes = new byte[1 + byteList.size() + 1];
        bytes[0] = (byte) byteList.size();
        for (int i = 0; i < byteList.size(); i++)
            bytes[i + 1] = byteList.get(i);
        return bytes;
    }

    public static String generateRequestRaw(ArrayList<Byte> payload) {
        byte[] bytes = listToBytes(payload);
        bytes[bytes.length - 1] = computeSRC8(payload);
//        System.out.§ln("bytes " + Arrays.toString(bytes));

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
        CmdBody cmdBody = new CmdBody();
        switch (this.cmd) {
            case 1, 2 -> {
                cmdBody.devName = decodeString(data, counter);
                switch (this.devType) {
                    case 2 -> {
                        cmdBody.sensorProps = new EnvSensorProps(data, counter);
                    }
                    case 3 -> {
                        cmdBody.connectedSwitch = new String[data[counter.value++]];
                        for (int i = 0; i < cmdBody.connectedSwitch.length; i++) {
                            cmdBody.connectedSwitch[i] = decodeString(data, counter);
                        }
                    }
                }
            }
            case 4 -> {
                switch (this.devType) {
                    case 2 -> cmdBody.sensorStatus = new EnvSensorStatus(data, counter);
                    case 3, 5 -> cmdBody.status = data[counter.value++];
                }
            }
            case 6 -> cmdBody.timestamp = Packet.fromULEB(data, counter);
        }
        this.cmdBody = cmdBody;
    }

    public static String decodeString(byte[] data, Counter counter) {
        StringBuilder builder = new StringBuilder();
        int stringLen = counter.value++;
        for (int i = 0; i < data[stringLen]; i++) {
            builder.append((char) data[counter.value++]);
        }
        return builder.toString();
    }

    @Override
    public String toString() {
        return "Payload{" +
                "src=" + src +
                ", dst=" + dst +
                ", serial=" + serial +
                ", devType=" + devType +
                ", cmd=" + cmd +
                ", cmdBody=" + cmdBody +
                '}';
    }
}

class CmdBody {
    public String devName;
    public long timestamp;
    public byte status;
    public String[] connectedSwitch;
    public EnvSensorProps sensorProps;
    public EnvSensorStatus sensorStatus;

    public CmdBody() {
    }

    @Override
    public String toString() {
        return "CmdBody{" +
                "devName='" + devName + '\'' +
                ", timestamp=" + timestamp +
                ", status=" + status +
                ", connectedSwitch=" + Arrays.toString(connectedSwitch) +
                ", sensorProps=" + sensorProps +
                ", sensorStatus=" + sensorStatus +
                '}';
    }
}

class Device {
    public int address;
    public String name = "";
    public int type;
    public long lastTimeUpdate;
    public boolean active = true;
    public byte status;
    public String[] connectedSwitch = null;
    public EnvSensorProps sensorProps;

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
                ", status=" + status +
                ", connectedSwitch=" + Arrays.toString(connectedSwitch) +
                ", sensorProps=" + sensorProps +
                '}';
    }
}

class EnvSensorProps {
    public boolean[] sensors;
    public Trigger[] triggers;

    public EnvSensorProps(byte[] data, Counter counter) {
        int sensors = data[counter.value++];
        this.sensors = new boolean[]{(sensors & 1) == 1, (sensors & 2) == 2, (sensors & 4) == 4, (sensors & 8) == 8};
        triggers = new Trigger[data[counter.value++]];
        for (int i = 0; i < triggers.length; i++) {
            Trigger trigger = new Trigger();
            int op = data[counter.value++];
            trigger.on = (byte) (op & 1);
            trigger.greater = (op & 2) == 2;
            trigger.sensorTypes = (op & 12) >> 2;
            trigger.value = (int) Packet.fromULEB(data, counter);
            trigger.name = Payload.decodeString(data, counter);
            triggers[i] = trigger;
        }
    }

    @Override
    public String toString() {
        return "EnvSensorProps{" +
                "sensors=" + Arrays.toString(sensors) +
                ", triggers=" + Arrays.toString(triggers) +
                '}';
    }
}

class Trigger {
    public byte on;
    public boolean greater;
    public int sensorTypes;
    public int value;
    public String name;

    @Override
    public String toString() {
        return "Trigger{" +
                "on=" + on +
                ", greater=" + greater +
                ", sensorTypes=" + sensorTypes +
                ", value=" + value +
                ", name='" + name + '\'' +
                '}';
    }
}

class EnvSensorStatus {
    public int[] values;

    public EnvSensorStatus(byte[] data, Counter counter) {
        int len = data[counter.value++];
        values = new int[len];
        for (int i = 0; i < len; i++) {
            values[i] = (int) Packet.fromULEB(data, counter);
        }
    }

    @Override
    public String toString() {
        return "EnvSensorStatus{" +
                "values=" + Arrays.toString(values) +
                '}';
    }
}

class Counter {
    public int value = 0;
}