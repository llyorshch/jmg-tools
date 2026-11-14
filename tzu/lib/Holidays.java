import java.io.IOException;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Public holidays from the free Nager.Date API (https://date.nager.at), cached in ~/.cache/tzu. */
final class Holidays {
    private Holidays() {}

    record Holiday(LocalDate date, String cc, String name, String localName, boolean national, List<String> regions, List<String> types) {}

    static List<Holiday> fetch(String cc, int year, boolean offline, boolean refresh) throws IOException {
        String url = "https://date.nager.at/api/v3/PublicHolidays/" + year + "/" + cc;
        Duration ttl = refresh ? Duration.ZERO : Duration.ofDays(30);
        String body = Net.cachedGet(url, "holidays/" + year + "-" + cc + ".json", ttl, offline);
        List<Holiday> out = new ArrayList<>();
        for (Map<String, Object> m : Net.list(Net.json(body))) {
            List<String> regions = new ArrayList<>();
            if (m.get("counties") instanceof List<?> l) for (Object o : l) regions.add(String.valueOf(o));
            List<String> types = new ArrayList<>();
            if (m.get("types") instanceof List<?> l) for (Object o : l) types.add(String.valueOf(o));
            out.add(new Holiday(LocalDate.parse(Net.str(m, "date")), cc, Net.str(m, "name"), Net.str(m, "localName"),
                    !Boolean.FALSE.equals(m.get("global")), regions, types));
        }
        return out;
    }

    /** ISO codes of the countries the API knows (cached for 30 days). */
    static List<String> available(boolean offline) {
        try {
            String body = Net.cachedGet("https://date.nager.at/api/v3/AvailableCountries", "holidays/countries.json",
                    Duration.ofDays(30), offline);
            List<String> out = new ArrayList<>();
            for (Map<String, Object> m : Net.list(Net.json(body))) out.add(Net.str(m, "countryCode"));
            return out;
        } catch (IOException e) {
            return null; // unknown
        }
    }
}
