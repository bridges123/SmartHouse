package src;

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
}