package src;

public class EnvSensorProps {
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
}
