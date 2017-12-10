package recommender;

import net.schmizz.sshj.SSHClient;
import net.schmizz.sshj.connection.channel.direct.Session;
import net.schmizz.sshj.transport.verification.PromiscuousVerifier;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class UAPClient implements Runnable {
    String ip;
    String username;
    String pw;
    String apMac;
    private static Pattern pattern = Pattern.compile("^[^\\[]*\\[([0-9a-z:]+)\\].([a-z.]+) idle= *[0-9]+ rssi= *([0-9]+).*");

    private static Map<String, ClientData> clientDataMap = new HashMap<>(500);

    private static class ClientData {
        String mac;
        String lastAPMac;
        Date lastTimestamp;
        int lastRssi;
        int lastLastRssi;
        boolean last5GHz;
    }

    public UAPClient(String ip, String username, String pw, String apMac) {
        this.ip = ip;
        this.username = username;
        this.pw = pw;
        this.apMac = apMac;
    }

    @Override
    public void run() {
        try {
            final SSHClient ssh = new SSHClient();
            ssh.addHostKeyVerifier(new PromiscuousVerifier());

            try {
                ssh.connect(ip);
                ssh.authPassword(username, pw);
                try (Session session = ssh.startSession()) {
                    session.allocateDefaultPTY();
                    final Session.Command cmd = session.exec("stainfo -a");
                    System.out.println(new Date() + " connected to UAP " + apAndName(apMac));
                    try (BufferedReader reader = new BufferedReader(new InputStreamReader(cmd.getInputStream()))) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            Matcher m = pattern.matcher(line);
                            if (m.matches()) {
                                String clientMac = m.group(1);
                                char[] connection = m.group(2).toCharArray();
                                boolean is5GHz =
                                        ((connection.length == 3) &&
                                                (connection[0] == 'a'))
                                                ||
                                        ((connection.length == 4) &&
                                                (connection[0] == 'a' || connection[1] == 'a'));

                                String rssi = m.group(3);
                                updateClientData(clientMac, apMac, Integer.valueOf(rssi), is5GHz);
                            }
                        }
                    }
                }
            } finally {
                System.out.println("ssh client disconnecting");
                ssh.disconnect();
            }
        } catch (Exception e) {
            System.out.println("ssh client thread died: " + e);
        }
    }

    private static synchronized void updateClientData(String clientMac, String apMac, int rssi, boolean is5GHz) {
        Date now = new Date();
        ClientData clientData = clientDataMap.get(clientMac);
        if (clientData == null || (now.getTime() - clientData.lastTimestamp.getTime() > (30*1000))) {
            // expired or new
            clientData = new ClientData();
            clientData.lastTimestamp = now;
            clientData.last5GHz = is5GHz;
            clientData.lastAPMac = apMac;
            clientData.lastRssi = rssi;
            clientData.lastLastRssi = rssi;
            clientData.mac = clientMac;

            clientDataMap.put(clientMac, clientData);

            System.out.println(now + " new client: " + clientMac + " at ap: " + apAndName(apMac));
        } else {
            // did it roam?
            if (clientData.last5GHz != is5GHz ||
                    !apMac.equals(clientData.lastAPMac)) {

                // it roamed
                System.out.println(now + " " + clientMac + " roamed from " + apAndName(clientData.lastAPMac) + " to " + apAndName(apMac));

                // if it roams at a low rssi, that means ap tx power too high
                // if it roams at a high rssi, that means ap tx power too low

                if (avg(clientData.lastRssi, clientData.lastLastRssi) < 16) {
                    System.out.println(now + " " + clientMac + " roamed from " + apAndName(clientData.lastAPMac) + " when rssi was " + clientData.lastRssi +
                            " and lastRssi was " + clientData.lastLastRssi +
                            "; tx power too HIGH on " + apAndName(clientData.lastAPMac));
                } else if (avg(clientData.lastRssi, clientData.lastLastRssi) > 25) {
                    System.out.println(now + " " + clientMac + " roamed from " + apAndName(clientData.lastAPMac) + " when rssi was " + clientData.lastRssi +
                            " and lastRssi was " + clientData.lastLastRssi +
                            "; tx power too LOW on " + apAndName(clientData.lastAPMac));
                }

                clientData.last5GHz = is5GHz;
                clientData.lastRssi = rssi;
                clientData.lastLastRssi = rssi;
                clientData.lastAPMac = apMac;
                clientData.lastTimestamp = now;
            } else {
                // associated to the same ap
                clientData.lastTimestamp = now;
                clientData.lastLastRssi = clientData.lastRssi;
                clientData.lastRssi = rssi;
            }
        }
    }

    private static int avg(int a, int b) {
        return (a+b)/2;
    }

    private static String apAndName(String mac) {
        return UnifiClient.uapData.get(mac, "name") + " (" + mac + ")";
    }
}
