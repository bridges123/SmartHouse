package src;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

public class Main {
    static int hub = 0;
    static URL url = null;

    public static void main(String[] args) {
        try {
            url = new URL(args[0]);
            hub = Integer.parseInt(args[1].trim(), 16);
        } catch (MalformedURLException e) {
            System.exit(99);
        }
        var smartHouse = new SmartHouse(hub, url);
        smartHouse.processReturnData(smartHouse.sendDataRequest(smartHouse.whoIsHereData()));
        List<Device> devices = smartHouse.getDevices();
        for (int i = 0; i < devices.size(); i++) {
            Device device = devices.get(i);
            smartHouse.processReturnData(
                    smartHouse.sendDataRequest(
                            smartHouse.getStatusData(device)
                    )
            );
        }
        while (true) {
            smartHouse.processReturnData(
                    smartHouse.sendDataRequest(
                            smartHouse.getStatusData(devices.get(0))
                    )
            );
        }
    }
}
