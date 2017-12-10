package recommender;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.HashBasedTable;
import com.google.common.collect.Table;
import org.apache.http.client.CookieStore;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.conn.ssl.NoopHostnameVerifier;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.*;
import org.apache.http.util.EntityUtils;

import java.io.IOException;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

public class UnifiClient {
    public static final String MODEL = "model";
    public static final String MAC = "mac";
    public static final String RADIO = "radio";
    public static final String CHANNEL = "channel";
    public static final String RADIO_TABLE = "radio_table";
    public static final String NA = "na";
    public static final String AUTO = "auto";
    public static final String HT = "ht";
    public static final String IP = "ip";
    public static final String USERNAME = "username";
    public static final String NG = "ng";
    public static final String DESC = "desc";
    public static final String NAME = "name";
    public static final String DATA = "data";
    public static final String X_SSH = "x_ssh_";
    public static final String PASSWORD = "password";
    private static CookieStore cookieStore = new BasicCookieStore();
    private static ObjectMapper mapper = new ObjectMapper();

    static Table<String, String, Object> uapData;

    private static List<String> models = Arrays.asList("U7MSH", "U7LR", "U7LT", "U7E", "U7Ev2", "U7PG2", "BZ2", "BZ2LR");
    private static List<String> valid24Channels = Arrays.asList("1","6","11");

    private static class Pair {
        private String a;
        private String b;
        
        private Pair() {}

        static Pair of(String a, String b) {
            Pair p = new Pair();
            p.a = a;
            p.b = b;
            
            return p;
        }
    }

    private static Object defaultIfNull(Object a, Object b) {
        return (a == null) ? b : a;
    }

    private static boolean isEmpty(String s) {
        return s == null || s.length() == 0;
    }

    public static void doit(String controllerHostname, String username, String pw, String site) {

        try (CloseableHttpClient httpClient = HttpClients.custom().setDefaultCookieStore(cookieStore).setSSLHostnameVerifier(NoopHostnameVerifier.INSTANCE).build()) {
            loginToController(controllerHostname, username, pw, httpClient);

            String siteId = getSiteId(controllerHostname, httpClient, site);

            System.out.println(new Date() + " retrieved site data for " + site);

            Pair uapCredentials = getUAPCredentials(controllerHostname, httpClient, siteId);

            System.out.println(new Date() + " retrieved credentials for " + site);

            checkWifiNetworkConfig(controllerHostname, httpClient, siteId);

            populateUAPData(controllerHostname, httpClient, siteId);

            System.out.println(new Date() + " general configuration examination complete");
            System.out.println();

            Map<String, Object> uapDataIPs = uapData.column(IP);
            if (uapDataIPs == null) {
                System.out.println("could not determine uap ip addresses");
                System.exit(1);
            }
            uapDataIPs.forEach((mac, ip) -> {
                        UAPClient client = new UAPClient((String) ip, uapCredentials.a, uapCredentials.b, mac);

                        (new Thread(client)).start();
                    }
            );
        } catch (Exception e) {
            System.out.print("got exception: " + e);
        }
    }

    @SuppressWarnings("unchecked")
    private static void checkWifiNetworkConfig(String controllerHostname, CloseableHttpClient httpclient, String siteId) throws IOException {
        HttpGet get = new HttpGet("https://" + controllerHostname + ":8443/api/s/" + siteId + "/rest/wlanconf");

        try (CloseableHttpResponse response = httpclient.execute(get)) {
            if (response == null || response.getStatusLine() == null || response.getStatusLine().getStatusCode() != 200 || response.getEntity() == null) {
                throw new RuntimeException("can't get site data for " + siteId);
            }

            String reString = EntityUtils.toString(response.getEntity());
            Map<String, Object> responseMap = mapper.readValue(reString, new TypeReference<Map<String, Object>>() {});

            Collection<Map<String, Object>> parameterMaps = (Collection<Map<String, Object>>) responseMap.get(DATA);
            parameterMaps
                    .stream()
                    .filter(m -> Boolean.TRUE.equals(m.get("enabled")))
                    .forEach(m -> {
                        String name = (String)m.get(NAME);

                        check_dtim("ng", "2G", name, m);
                        check_dtim("na", "5G", name, m);
                        check_2G_rate(name, m);
                    });
        }
    }

    private static void check_dtim(String tech, String band_desc, String name, Map<String, Object> m) {
        Integer dtimValue = (Integer)m.get("dtim_" + tech);

        if (dtimValue == null || dtimValue < 3) {
            System.out.println("Consider increasing " + band_desc + " DTIM Period to 3 or greater on Wireless Network " + name);
        }
    }

    private static void check_2G_rate(String name, Map<String, Object> m) {
        Integer dataRate = (Integer)m.get("minrate_ng_data_rate_kbps");
        Integer beaconRate = (Integer)m.get("minrate_ng_beacon_rate_kbps");
        Boolean adRates = (Boolean)m.get("minrate_ng_advertising_rates");

        if ((dataRate == null || beaconRate == null || adRates == null) ||
                (dataRate < 12000 && dataRate != 6000) ||
                (!adRates)) {
            System.out.println("If there are no 802.11b devices on the network, consider increasing the 2G Data Rate Control to 6Mbps, or 12Mbps or higher, on Wireless Network " + name);
        }
    }

    @SuppressWarnings("unchecked")
    private static void populateUAPData(String controllerHostname, CloseableHttpClient httpclient, String siteId) throws IOException {
        HttpGet get = new HttpGet("https://" + controllerHostname + ":8443/api/s/" + siteId + "/stat/device");

        try (CloseableHttpResponse response = httpclient.execute(get)) {
            if (response == null || response.getStatusLine() == null || response.getStatusLine().getStatusCode() != 200 || response.getEntity() == null) {
                throw new RuntimeException("can't get site data for " + siteId);
            }

            uapData = HashBasedTable.create();

            String reString = EntityUtils.toString(response.getEntity());
            Map<String, Object> responseMap = mapper.readValue(reString, new TypeReference<Map<String, Object>>() {});

            List<String> channels5GHz = new ArrayList<>();
            List<String> channels24GHz = new ArrayList<>();

            ((Collection<Map<String, Object>>) responseMap.get(DATA))
                    .stream()
                    .filter(m -> models.contains(m.get(MODEL)))
                    .forEach(m -> {
                        String mac = (String)m.get(MAC);
                        uapData.put(mac, IP, m.get(IP));
                        String name = m.get(NAME).toString();
                        uapData.put(mac, NAME, name);
                        ((Collection<Map<String, Object>>) m.get(RADIO_TABLE))
                                .forEach(rt -> {
                                    String radio = (String) rt.get(RADIO);
                                    String channel = defaultIfNull(rt.get(CHANNEL), m.get(radio + "-channel")).toString();

                                    Object htSize = rt.get(HT);
                                    switch (radio) {
                                        case NA:
                                            if (AUTO.equals(channel)) {
                                                System.out.println("I recommend using an explicit channel instead of the non-deterministic \"auto\" channel on 5GHz interface of " + name + " (" + mac + ")");
                                            } else {
                                                channels5GHz.add(channel);
                                            }
                                            if ("20".equals(htSize)) {
                                                System.out.println("Consider using greater than 20MHz bandwidth on 5GHz interface of " + name + " (" + mac + ")");
                                            }
                                            break;
                                        case NG:
                                            if (htSize != null && !"20".equals(htSize)) {
                                                System.out.println("I recommend using 20MHz bandwidth on 2.4GHz interface of " + name + " (" + mac + ")");
                                            }
                                            if (AUTO.equals(channel)) {
                                                System.out.println("I recommend using an explicit channel instead of the non-deterministic \"auto\" channel on 2.4GHz interface of " + name + " (" + mac + ")");
                                            } else {
                                                channels24GHz.add(channel);
                                                if (!valid24Channels.contains(channel)) {
                                                    System.out.println("I strongly recommend using channel 1, 6, or 11 on 2.4GHz interface of " + name + " (" + mac + ") to avoid Adjacent Channel Interference");
                                                }
                                            }
                                            break;
                                        default:
                                    }
                                });
                    });

            Map<String, Long> count5GHzChannels = channels5GHz.stream().collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));
            Map<String, Long> count24GHzChannels = channels24GHz.stream().collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));

            analyzeForSameChannels5(count5GHzChannels);
            analyzeForSameChannels24(count24GHzChannels);
        }
    }

    private static void analyzeForSameChannels5(Map<String, Long> chanMap) {
        analyzeForSameChannels(chanMap, "5GHz", 2);
    }

    private static void analyzeForSameChannels24(Map<String, Long> chanMap) {
        if (chanMap.keySet().stream().anyMatch(k -> !valid24Channels.contains(k))) {
            // not even worth tying if the channels are non-{1,6,11}
            return;
        }

        analyzeForSameChannels(chanMap, "2.4GHz", 3);
    }

    private static void analyzeForSameChannels(Map<String, Long> chanMap, String band, int numChansLimit) {
        if (chanMap.size() == 0) {
            return;
        }

        Long min = chanMap.values().stream().min(Comparator.comparingLong(value -> value)).orElse(0L);
        Long max = chanMap.values().stream().max(Comparator.comparingLong(value -> value)).orElse(Long.MAX_VALUE);

        if (chanMap.size() < numChansLimit) {
            if (max > 1) {
                System.out.println("Use separate, non-overlapping channels in the " + band + " band, to minimize Co-Channel Interference");
            }
            return;
        }

        if (max-min > 1) {
            System.out.println("Inconsistent distribution of channels across access points in " + band);
        }
    }

    @SuppressWarnings("unchecked")
    private static Pair getUAPCredentials(String controllerHostname, CloseableHttpClient httpclient, String siteId) throws IOException {
        if (siteId == null) {
            throw new RuntimeException("could not find siteId");
        }

        HttpGet get = new HttpGet("https://" + controllerHostname + ":8443/api/s/" + siteId + "/get/setting");

        try (CloseableHttpResponse response = httpclient.execute(get)) {
            if (response == null || response.getStatusLine() == null || response.getStatusLine().getStatusCode() != 200 || response.getEntity() == null) {
                throw new RuntimeException("can't get site data for " + siteId);
            }

            String reString = EntityUtils.toString(response.getEntity());
            Map<String, Object> responseMap = mapper.readValue(reString, new TypeReference<Map<String, Object>>() {});

            return ((Collection<Map<String, Object>>) responseMap.get(DATA))
                    .stream()
                    .filter(m -> {
                        Boolean enabled = (Boolean) m.get(X_SSH + "enabled");
                        return enabled != null && enabled;
                    })
                    .map(m -> {
                        String username = (String)m.get(X_SSH + USERNAME);
                        String password = (String)m.get(X_SSH + PASSWORD);

                        return Pair.of(username, password);
                    })
                    .filter(p -> !isEmpty(p.a) && !isEmpty(p.b))
                    .findFirst()
                    .orElseThrow(() -> new RuntimeException("SSH Authentication must be enabled for " + siteId));
        }
    }

    @SuppressWarnings("unchecked")
    private static String getSiteId(String controllerHostname, CloseableHttpClient httpclient, String site) throws IOException {
        HttpGet get = new HttpGet("https://" + controllerHostname + ":8443/api/self/sites");

        try (CloseableHttpResponse response = httpclient.execute(get)) {
            if (response == null || response.getStatusLine() == null || response.getStatusLine().getStatusCode() != 200 || response.getEntity() == null) {
                throw new RuntimeException("can't get sites");
            }

            String reString = EntityUtils.toString(response.getEntity());
            Map<String, Object> responseMap = mapper.readValue(reString, new TypeReference<Map<String, Object>>() {});

            return ((Collection<Map<String, Object>>) responseMap.get(DATA))
                    .stream()
                    .filter(m -> site.equals(m.get(DESC)) || site.equals(m.get(NAME)))
                    .map(m -> (String)m.get(NAME))
                    .findFirst()
                    .orElseThrow(() -> new RuntimeException("could not retrieve data for site " + site));
        }
    }

    private static void loginToController(String controllerHostname, String username, String pw, CloseableHttpClient httpclient) throws IOException {
        HttpPost post = new HttpPost("https://" + controllerHostname + ":8443/api/login");
        Map<String, Object> loginJson = new HashMap<>();
        loginJson.put("username", username);
        loginJson.put("password", pw);
        loginJson.put("remember", true);
        loginJson.put("strict", true);
        StringEntity entity = new StringEntity(mapper.writeValueAsString(loginJson));
        entity.setContentType("application/json");
        post.setEntity(entity);
        post.setHeader("Accept", "application/json");
        post.setHeader("Content-type", "application/json");

        try (CloseableHttpResponse response = httpclient.execute(post)) {
            if (response == null || response.getStatusLine() == null || response.getStatusLine().getStatusCode() != 200) {
                throw new RuntimeException("could not log in");
            }
        }

        System.out.println(new Date() + " logged into controller");
    }
}
