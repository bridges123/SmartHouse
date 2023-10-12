package src;

import java.io.*;
import java.net.*;
import java.util.*;

public class SmartHouse {
    private long time = 0, serial = 1;
    private int hub;
    private URL url;
    private final String hubName = "HUB01";
    private final List<Device> devices = new ArrayList<>();

    public SmartHouse(int hub, URL url) {
        this.hub = hub;
        this.url = url;
    }

    public List<Device> getDevices() {
        return devices;
    }

    public String sendDataRequest(String data) {
        String raw = requestServer(data);
        if (Objects.equals(raw, "")) System.exit(99);
        return raw;
    }

    // обработка ответа
    public void processReturnData(String raw) {
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
                Device device = getOrCreateDevice(packet.payload);
                // обрабатываем кейсы комбинаций (устройство, команда)
                initCommandsProcess(device, packet.payload);
                activeCommandsProcess(device, packet.payload);
            }
        }
    }

    public void initCommandsProcess(Device device, Payload payload) {
        if (payload.cmd == 1 || payload.cmd == 2) {
            device.active = true;
            if (payload.devType == 3) {
                if (payload.cmdBody.connectedSwitch != null)
                    device.connectedSwitch = payload.cmdBody.connectedSwitch;
            }
            if (payload.devType == 2) {
                device.sensorProps = payload.cmdBody.sensorProps;
            }
            if (payload.cmd == 1) {
                processReturnData(sendDataRequest(IAmHereData()));
            }
            processReturnData(sendDataRequest(getStatusData(device)));
        }
    }

    public void activeCommandsProcess(Device device, Payload payload) {
        if (device.active) {
            if (payload.cmd == 4) {
                if (payload.devType == 2) {
                    sensorStatusProcess(payload.cmdBody.sensorStatus,
                            payload.cmdBody.sensorProps);
                }
                if (payload.devType == 3 || payload.devType == 4 || payload.devType == 5) {
                    device.status = payload.cmdBody.status;
                    if (payload.devType == 3) {
                        if (device.active && device.connectedSwitch != null)
                            switchTurnConnectedDevices(device);
                    }
                }
            }
            device.lastTimeUpdate = time;
        }
    }

    public Device getOrCreateDevice(Payload payload) {
        Device device;
        // если id устройства пакета есть в списке девайсов, берем индекс, иначе добавляем
        Optional<Device> optDevice = devices.stream()
                .filter(d -> d.address == payload.src)
                .findFirst();
        if (optDevice.isPresent()) {
            device = optDevice.get();
        } else {
            device = new Device(payload.src, payload.devType);
            if (payload.cmd == 1 || payload.cmd == 2)
                device.name = payload.cmdBody.devName;
            devices.add(device);
        }
        return device;
    }

    public void sensorStatusProcess(EnvSensorStatus sensorStatus, EnvSensorProps sensorProps) {
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

    // вкл/выкл подключенных к switch девайсов
    public void switchTurnConnectedDevices(Device switchDev) {
        for (String deviceName : switchDev.connectedSwitch) {
            Optional<Device> optDevice = devices.stream().filter(d -> d.name.equals(deviceName)).findFirst();
            if (optDevice.isPresent()) {
                Device connDev = optDevice.get();
                connDev.status = 1;

                String raw = sendDataRequest(setStatusData(connDev, switchDev.status));
                processReturnData(raw);
            }
        }
    }

    public String requestServer(String data) {
        String raw = "";
        try {
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            connection.setRequestProperty("Content-Length", Integer.toString(data.length()));
            try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(connection.getOutputStream()))) {
                writer.write(data);
            }
            if (connection.getResponseCode() == 204) {
                System.exit(0);
            } else if (connection.getResponseCode() != 200) {
                System.exit(99);
            }

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()))) {
                raw = reader.readLine();
            }
            if (raw.length() % 4 == 2) raw += "==";
            else if (raw.length() % 4 == 3) raw += "=";
            raw = raw.replace('-', '+').replace('_', '/');
        } catch (Exception ex) {
            System.exit(99);
        }
        return raw;
    }

    public String whoIsHereData() {
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

    public String IAmHereData() {
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

    public String getStatusData(Device device) {
        ArrayList<Byte> payload = new ArrayList<>();
        Packet.toULEB(hub, payload);
        Packet.toULEB(device.address, payload);
        Packet.toULEB((int) serial++, payload);
        payload.add((byte) device.type); // devType
        payload.add((byte) 3); // cmd

        return generateRequestRaw(payload);
    }

    public String setStatusData(Device device, byte status) {
        ArrayList<Byte> payload = new ArrayList<>();
        Packet.toULEB(hub, payload);
        Packet.toULEB(device.address, payload);
        Packet.toULEB((int) serial++, payload);
        payload.add((byte) device.type); // devType
        payload.add((byte) 5); // cmd
        payload.add(status); // cmdBody

        return generateRequestRaw(payload);
    }

    public byte[] listToBytes(ArrayList<Byte> byteList) {
        byte[] bytes = new byte[1 + byteList.size() + 1];
        bytes[0] = (byte) byteList.size();
        for (int i = 0; i < byteList.size(); i++)
            bytes[i + 1] = byteList.get(i);
        return bytes;
    }

    public String generateRequestRaw(ArrayList<Byte> payload) {
        byte[] bytes = listToBytes(payload);
        bytes[bytes.length - 1] = computeSRC8(payload);
        return Base64.getEncoder().encodeToString(bytes).replace("=", "").replace('+', '-').replace('/', '_');
    }

    public byte computeSRC8(ArrayList<Byte> bytes) {
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