import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Embedded geographic data (GeoNames cities and countries, IANA zone.tab) and place lookup. */
final class Geo {
    private Geo() {}

    record City(String name, String ascii, String cc, String region, double lat, double lon, long population, ZoneId zone) {
        String key() { return norm(ascii.isEmpty() ? name : ascii); }
    }

    record Country(String iso, String name, String continent, String capital, List<String> zones) {}

    /** A resolved place: what the user asked for, mapped to a zone. */
    record Place(String label, ZoneId zone, String kind, String cc, Double lat, Double lon, long population, String region) {
        static Place ofZone(String label, ZoneId z) { return new Place(label, z, "zone", countryOf(z), null, null, 0, ""); }
    }

    private static List<City> cities;
    private static Map<String, Country> countries;
    private static Map<String, String> zoneCountry;

    static synchronized List<City> cities() {
        if (cities == null) {
            List<City> list = new ArrayList<>(8000);
            for (String[] p : readTsv("data/cities.tsv")) {
                try {
                    list.add(new City(p[0], p[1], p[2], p[3], Double.parseDouble(p[4]), Double.parseDouble(p[5]),
                            Long.parseLong(p[6]), ZoneId.of(p[7])));
                } catch (DateTimeException ignored) {
                    // zone unknown to this JDK's tzdb
                }
            }
            cities = list;
        }
        return cities;
    }

    static synchronized Map<String, Country> countries() {
        if (countries == null) {
            Map<String, Country> m = new TreeMap<>();
            Map<String, String> zc = new HashMap<>();
            for (String[] p : readTsv("data/countries.tsv")) {
                List<String> zones = List.of(p[4].split(","));
                m.put(p[0], new Country(p[0], p[1], p[2], p[3], zones));
                for (String z : zones) zc.putIfAbsent(z, p[0]);
            }
            countries = m;
            zoneCountry = zc;
        }
        return countries;
    }

    static String countryName(String cc) {
        Country c = countries().get(cc);
        return c == null ? cc : c.name();
    }

    /** ISO country code for a zone, or "" (UTC, Etc/..). */
    static String countryOf(ZoneId zone) {
        countries();
        String cc = zoneCountry.get(zone.getId());
        if (cc == null) {
            // Links / legacy names (e.g. Asia/Calcutta) -> look for a city in that zone
            for (City c : cities()) if (c.zone().getId().equals(zone.getId())) return c.cc();
            return "";
        }
        return cc;
    }

    /** The zone most people in a country use: the capital's zone, else the first zone.tab entry. */
    static ZoneId mainZone(String cc) {
        Country c = countries().get(cc);
        if (c == null) return ZoneOffset.UTC;
        for (City city : cities()) {
            if (city.cc().equals(cc) && norm(city.name()).equals(norm(c.capital()))) return city.zone();
        }
        // the most populated city is a good proxy too
        for (City city : cities()) if (city.cc().equals(cc)) return city.zone();
        return ZoneId.of(c.zones().get(0));
    }

    /** Human label for a zone id: "America/Argentina/Buenos_Aires" -> "Buenos Aires". */
    static String labelFor(ZoneId zone) {
        String id = zone.getId();
        if (id.equals("Z") || id.equals("UTC") || id.equals("Etc/UTC") || id.equals("GMT")) return "UTC";
        int slash = id.lastIndexOf('/');
        String last = slash >= 0 ? id.substring(slash + 1) : id;
        return last.replace('_', ' ');
    }

    static String norm(String s) {
        String n = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        return n.toLowerCase(Locale.ROOT).replace('_', ' ').replace('-', ' ').replaceAll("\\s+", " ").trim();
    }

    // ------------------------------------------------------------------ abbreviations

    /** Common abbreviations that are ambiguous or unknown to java.time. */
    static final Map<String, String> ABBREVIATIONS = Map.ofEntries(
            Map.entry("pst", "America/Los_Angeles"), Map.entry("pdt", "America/Los_Angeles"), Map.entry("pt", "America/Los_Angeles"),
            Map.entry("mst", "America/Denver"), Map.entry("mdt", "America/Denver"), Map.entry("mt", "America/Denver"),
            Map.entry("cst", "America/Chicago"), Map.entry("cdt", "America/Chicago"), Map.entry("ct", "America/Chicago"),
            Map.entry("est", "America/New_York"), Map.entry("edt", "America/New_York"), Map.entry("et", "America/New_York"),
            Map.entry("akst", "America/Anchorage"), Map.entry("hst", "Pacific/Honolulu"),
            Map.entry("ast", "America/Halifax"), Map.entry("nst", "America/St_Johns"),
            Map.entry("brt", "America/Sao_Paulo"), Map.entry("art", "America/Argentina/Buenos_Aires"),
            Map.entry("gmt", "Europe/London"), Map.entry("bst", "Europe/London"), Map.entry("wet", "Europe/Lisbon"),
            Map.entry("cet", "Europe/Paris"), Map.entry("cest", "Europe/Paris"),
            Map.entry("eet", "Europe/Athens"), Map.entry("eest", "Europe/Athens"), Map.entry("msk", "Europe/Moscow"),
            Map.entry("ist", "Asia/Kolkata"), Map.entry("pkt", "Asia/Karachi"), Map.entry("gst", "Asia/Dubai"),
            Map.entry("ict", "Asia/Bangkok"), Map.entry("wib", "Asia/Jakarta"), Map.entry("sgt", "Asia/Singapore"),
            Map.entry("hkt", "Asia/Hong_Kong"), Map.entry("pht", "Asia/Manila"),
            Map.entry("jst", "Asia/Tokyo"), Map.entry("kst", "Asia/Seoul"),
            Map.entry("awst", "Australia/Perth"), Map.entry("acst", "Australia/Adelaide"),
            Map.entry("aest", "Australia/Sydney"), Map.entry("aedt", "Australia/Sydney"),
            Map.entry("nzst", "Pacific/Auckland"), Map.entry("nzdt", "Pacific/Auckland"),
            Map.entry("sast", "Africa/Johannesburg"), Map.entry("cat", "Africa/Maputo"),
            Map.entry("eat", "Africa/Nairobi"), Map.entry("wat", "Africa/Lagos"));

    private static final Pattern OFFSET = Pattern.compile("(?i)^(?:utc|gmt)?\\s*([+-])\\s*(\\d{1,2})(?::?(\\d{2}))?$");

    static Optional<ZoneOffset> parseOffset(String s) {
        Matcher m = OFFSET.matcher(s.trim());
        if (!m.matches()) return Optional.empty();
        int h = Integer.parseInt(m.group(2));
        int min = m.group(3) == null ? 0 : Integer.parseInt(m.group(3));
        int sign = m.group(1).equals("-") ? -1 : 1;
        try {
            return Optional.of(ZoneOffset.ofHoursMinutes(sign * h, sign * min));
        } catch (DateTimeException e) {
            return Optional.empty();
        }
    }

    private static Map<String, String> zoneIdsByNorm;

    private static synchronized Map<String, String> zoneIdsByNorm() {
        if (zoneIdsByNorm == null) {
            Map<String, String> m = new HashMap<>();
            for (String id : ZoneId.getAvailableZoneIds()) {
                m.put(norm(id), id);
                int slash = id.lastIndexOf('/');
                if (slash > 0 && !id.startsWith("Etc/") && !id.startsWith("SystemV/")) m.putIfAbsent(norm(id.substring(slash + 1)), id);
            }
            zoneIdsByNorm = m;
        }
        return zoneIdsByNorm;
    }

    // ------------------------------------------------------------------ resolution

    /** Resolves free text (zone id, abbreviation, offset, country, city) to the single best place. */
    static Optional<Place> resolve(String input) {
        if (input == null || input.isBlank()) return Optional.empty();
        String q = input.trim();
        String n = norm(q);
        if (n.equals("local") || n.equals("here")) {
            ZoneId z = ZoneId.systemDefault();
            return Optional.of(Place.ofZone(labelFor(z), z));
        }
        if (n.equals("utc") || n.equals("z") || n.equals("zulu")) return Optional.of(Place.ofZone("UTC", ZoneId.of("UTC")));
        // exact zone id (case insensitive, spaces allowed)
        String id = zoneIdsByNorm().get(n);
        if (id != null && (n.contains("/") || norm(q).equals(norm(id)))) {
            ZoneId z = ZoneId.of(id);
            return Optional.of(Place.ofZone(labelFor(z), z));
        }
        var off = parseOffset(q);
        if (off.isPresent()) return Optional.of(Place.ofZone("UTC" + off.get().getId(), off.get()));
        String abbr = ABBREVIATIONS.get(n);
        if (abbr != null) return Optional.of(Place.ofZone(q.toUpperCase(Locale.ROOT), ZoneId.of(abbr)));
        // country code / name
        Country c = findCountry(q);
        if (c != null && (q.length() == 2 || norm(c.name()).equals(n))) {
            ZoneId z = mainZone(c.iso());
            return Optional.of(new Place(c.name(), z, "country", c.iso(), null, null, 0, c.continent()));
        }
        // exact city
        String cityPart = n;
        String ccFilter = null;
        int comma = n.lastIndexOf(',');
        if (comma > 0) {
            cityPart = n.substring(0, comma).trim();
            String rest = n.substring(comma + 1).trim();
            Country cf = findCountry(rest);
            ccFilter = cf != null ? cf.iso() : null;
        }
        for (City city : cities()) {
            if ((ccFilter == null || city.cc().equals(ccFilter))
                    && (norm(city.name()).equals(cityPart) || city.key().equals(cityPart))) {
                return Optional.of(cityPlace(city));
            }
        }
        // zone id suffix ("new york", "kolkata")
        if (id != null) {
            ZoneId z = ZoneId.of(id);
            return Optional.of(Place.ofZone(labelFor(z), z));
        }
        if (c != null) {
            ZoneId z = mainZone(c.iso());
            return Optional.of(new Place(c.name(), z, "country", c.iso(), null, null, 0, c.continent()));
        }
        // city prefix
        for (City city : cities()) {
            if ((ccFilter == null || city.cc().equals(ccFilter))
                    && (norm(city.name()).startsWith(cityPart) || city.key().startsWith(cityPart))) {
                return Optional.of(cityPlace(city));
            }
        }
        return Optional.empty();
    }

    static Place cityPlace(City city) {
        return new Place(city.name(), city.zone(), "city", city.cc(), city.lat(), city.lon(), city.population(), city.region());
    }

    static Country findCountry(String q) {
        String n = norm(q);
        if (n.isEmpty()) return null;
        if (n.length() == 2) {
            Country c = countries().get(n.toUpperCase(Locale.ROOT));
            if (c != null) return c;
        }
        Map<String, String> aliases = Map.of("usa", "US", "uk", "GB", "england", "GB", "britain", "GB",
                "great britain", "GB", "uae", "AE", "russia", "RU", "south korea", "KR", "korea", "KR");
        if (aliases.containsKey(n)) return countries().get(aliases.get(n));
        if (n.length() == 3) {
            // ISO3 isn't embedded; use Locale for the common ones
            for (String iso : Locale.getISOCountries()) {
                try {
                    if (Locale.of("", iso).getISO3Country().equalsIgnoreCase(n)) return countries().get(iso);
                } catch (Exception ignored) { }
            }
        }
        for (Country c : countries().values()) if (norm(c.name()).equals(n)) return c;
        for (Country c : countries().values()) if (norm(c.name()).startsWith(n) && n.length() >= 3) return c;
        return null;
    }

    /** All matches for a free text query, best first. */
    static List<Place> search(String input, int limit) {
        String q = input.trim();
        String n = norm(q);
        LinkedHashMap<String, Place> out = new LinkedHashMap<>();
        if (n.isEmpty()) return List.of();

        // offsets: list zones currently at that offset
        var off = parseOffset(q);
        if (off.isPresent()) {
            Instant now = Instant.now();
            out.put("off", Place.ofZone("UTC" + off.get().getId(), off.get()));
            for (Country c : countries().values()) {
                for (String z : c.zones()) {
                    ZoneId zid = ZoneId.of(z);
                    if (zid.getRules().getOffset(now).equals(off.get())) {
                        out.putIfAbsent(z, new Place(labelFor(zid), zid, "zone", c.iso(), null, null, 0, ""));
                    }
                }
            }
            return trim(out, limit);
        }
        String abbr = ABBREVIATIONS.get(n);
        if (abbr != null) out.put(abbr, new Place(q.toUpperCase(Locale.ROOT), ZoneId.of(abbr), "abbreviation", countryOf(ZoneId.of(abbr)), null, null, 0, ""));
        // abbreviations as reported by the JDK (e.g. "CEST", "AEDT")
        if (n.length() >= 3 && n.length() <= 5 && n.matches("[a-z]+")) {
            Instant now = Instant.now();
            for (String id : ZoneId.getAvailableZoneIds()) {
                if (!id.contains("/") || id.startsWith("Etc/") || id.startsWith("SystemV/")) continue;
                ZoneId z = ZoneId.of(id);
                if (!countries().containsKey(countryOf(z))) continue;
                boolean dst = z.getRules().isDaylightSavings(now);
                String shortName = java.util.TimeZone.getTimeZone(z).getDisplayName(dst, java.util.TimeZone.SHORT, Locale.ENGLISH);
                if (shortName.equalsIgnoreCase(q) && out.size() < limit) {
                    out.putIfAbsent(id, new Place(labelFor(z), z, "abbreviation", countryOf(z), null, null, 0, ""));
                }
            }
        }
        // zone ids
        for (String id : ZoneId.getAvailableZoneIds()) {
            if (id.startsWith("SystemV/") || id.startsWith("Etc/GMT")) continue;
            String nid = norm(id);
            String last = norm(labelFor(ZoneId.of(id)));
            if (nid.equals(n) || last.equals(n) || (n.length() >= 3 && (last.startsWith(n) || (n.contains("/") && nid.startsWith(n))))) {
                ZoneId z = ZoneId.of(id);
                out.putIfAbsent(id, new Place(labelFor(z), z, "zone", countryOf(z), null, null, 0, ""));
            }
        }
        // countries: every zone of the country
        Country c = findCountry(q);
        if (c != null) {
            for (String z : c.zones()) {
                ZoneId zid = ZoneId.of(z);
                City biggest = null;
                for (City city : cities()) {
                    if (city.zone().getId().equals(z) && city.cc().equals(c.iso())) { biggest = city; break; }
                }
                Place p = biggest != null
                        ? new Place(biggest.name(), zid, "country", c.iso(), biggest.lat(), biggest.lon(), biggest.population(), biggest.region())
                        : new Place(labelFor(zid), zid, "country", c.iso(), null, null, 0, "");
                out.putIfAbsent("country:" + z, p);
            }
        }
        // cities: exact, then prefix, then contains (cities are sorted by population)
        List<City> exact = new ArrayList<>(), prefix = new ArrayList<>(), contains = new ArrayList<>();
        for (City city : cities()) {
            String a = norm(city.name()), b = city.key();
            if (a.equals(n) || b.equals(n)) exact.add(city);
            else if (a.startsWith(n) || b.startsWith(n)) prefix.add(city);
            else if (n.length() >= 5 && !out.values().stream().anyMatch(p -> p.kind().equals("abbreviation"))
                    && (a.contains(n) || b.contains(n))) contains.add(city);
        }
        for (List<City> l : List.of(exact, prefix, contains)) {
            for (City city : l) out.putIfAbsent("city:" + city.name() + city.cc() + city.lat(), cityPlace(city));
        }
        List<Place> res = new ArrayList<>(out.values());
        // a zone named like a city we already list is redundant ("Istanbul" zone vs "Istanbul" city)
        res.removeIf(p -> p.kind().equals("zone") && res.stream().anyMatch(o -> o != p && o.kind().equals("city")
                && o.zone().equals(p.zone()) && norm(o.label()).startsWith(norm(p.label()))));
        // exact city matches first when the query isn't an obvious zone/country
        res.sort(Comparator.comparingInt(p -> rank(p, n)));
        return res.size() > limit ? res.subList(0, limit) : res;
    }

    private static int rank(Place p, String n) {
        boolean exactLabel = norm(p.label()).equals(n);
        return switch (p.kind()) {
            case "abbreviation" -> 0;
            case "city" -> exactLabel ? 1 : 4;
            case "zone" -> exactLabel ? 2 : 5;
            case "country" -> 3;
            default -> 6;
        };
    }

    private static List<Place> trim(LinkedHashMap<String, Place> m, int limit) {
        List<Place> l = new ArrayList<>(m.values());
        return l.size() > limit ? l.subList(0, limit) : l;
    }

    /** Representative cities per zone (biggest first) — used by the map and finder. */
    static Map<String, List<City>> citiesByZone() {
        Map<String, List<City>> m = new HashMap<>();
        for (City c : cities()) m.computeIfAbsent(c.zone().getId(), k -> new ArrayList<>()).add(c);
        return m;
    }

    /** Distinct zones of the given country codes, main zone first. */
    static Set<ZoneId> zonesOf(String cc) {
        Set<ZoneId> s = new LinkedHashSet<>();
        s.add(mainZone(cc));
        Country c = countries().get(cc);
        if (c != null) for (String z : c.zones()) s.add(ZoneId.of(z));
        return s;
    }

    static String displayName(ZoneId z, boolean dst) {
        String s = java.util.TimeZone.getTimeZone(z).getDisplayName(dst, java.util.TimeZone.SHORT, Locale.ENGLISH);
        if (s.startsWith("GMT") && s.length() > 3) {
            // no real abbreviation: "GMT-03:00" -> "-03", "GMT+05:45" -> "+0545"
            String o = s.substring(3).replace(":", "");
            return o.endsWith("00") ? o.substring(0, o.length() - 2) : o;
        }
        return s;
    }

    static String longName(ZoneId z) {
        return z.getDisplayName(TextStyle.FULL, Locale.ENGLISH);
    }

    // ------------------------------------------------------------------ resources

    static List<String[]> readTsv(String resource) {
        List<String[]> rows = new ArrayList<>();
        try (InputStream in = open(resource);
             BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.isEmpty() || line.startsWith("#")) continue;
                rows.add(line.split("\t", -1));
            }
        } catch (IOException e) {
            throw new IllegalStateException("cannot read embedded resource " + resource, e);
        }
        return rows;
    }

    static InputStream open(String resource) throws IOException {
        InputStream in = Geo.class.getClassLoader().getResourceAsStream(resource);
        if (in == null) in = Geo.class.getResourceAsStream("/" + resource);
        if (in == null) throw new IOException("resource not found: " + resource);
        return in;
    }
}
