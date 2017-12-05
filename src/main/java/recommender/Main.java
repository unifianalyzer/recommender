package recommender;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Main {
    private static final Logger log = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) {
        System.out.println("For help or info, see https://sites.google.com/view/uap-tuning-app\n");
        if ((args.length != 4) || (args[0].equals("--help")) || (args[0].equals("-help"))) {
            System.out.println("Usage:\n");
            System.out.println("java -jar <path_to_recommender.jar> <unifi_controller_host_or_ip> <unifi_username> <unifi_pw> <site_name_or_id>");

            System.exit(1);
        }

        String controllerHostname = args[0];
        String username = args[1];
        String pw = args[2];
        String site = args[3];

        UnifiClient.doit(controllerHostname, username, pw, site);
    }
}
