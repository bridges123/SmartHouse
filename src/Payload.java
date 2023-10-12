package src;

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
}