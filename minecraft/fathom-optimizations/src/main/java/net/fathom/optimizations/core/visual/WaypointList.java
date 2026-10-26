package net.fathom.optimizations.core.visual;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Waypoints, without Minecraft: what is stored, where it belongs, and the
 * {@code /fwp} command that edits it.
 *
 * <p>Every waypoint belongs to a place: a server address, or a singleplayer
 * world by name, plus a dimension. Only the current place's waypoints are
 * drawn, so a bed marked on one server does not appear on another.
 *
 * <p>Stored one per line, tab separated, so the file can be read and fixed by
 * hand: place, dimension, name, x, y, z, colour.
 */
public final class WaypointList {

    /** One marked spot. */
    public static final class Waypoint {
        public final String place;
        public final int dimension;
        public final String name;
        public final int x;
        public final int y;
        public final int z;
        public final int rgb;

        public Waypoint(String place, int dimension, String name, int x, int y, int z, int rgb) {
            this.place = place;
            this.dimension = dimension;
            this.name = name;
            this.x = x;
            this.y = y;
            this.z = z;
            this.rgb = rgb & 0xFFFFFF;
        }
    }

    /** The colours new waypoints take in turn. */
    public static final int[] COLOURS = {0x5B9BD5, 0x4FC98A, 0xE0AE57, 0xB37FE8, 0xE0505C, 0xFFFFFF};

    /** The name the death waypoint is kept under; a new death replaces the old one. */
    public static final String DEATH = "Death";

    private final List<Waypoint> all = new ArrayList<Waypoint>();
    private int revision;

    /** Changes with every edit, so the caller knows when to save. */
    public int revision() {
        return revision;
    }

    public List<Waypoint> all() {
        return Collections.unmodifiableList(all);
    }

    /** The waypoints of one place and dimension, in the order they were made. */
    public List<Waypoint> at(String place, int dimension) {
        List<Waypoint> out = new ArrayList<Waypoint>();
        for (Waypoint each : all) {
            if (each.place.equals(place) && each.dimension == dimension) {
                out.add(each);
            }
        }
        return out;
    }

    /** Adds one, replacing a waypoint of the same name in the same place. */
    public Waypoint add(String place, int dimension, String name, int x, int y, int z, int rgb) {
        remove(place, name);
        Waypoint made = new Waypoint(place, dimension, clean(name), x, y, z, rgb);
        all.add(made);
        revision++;
        return made;
    }

    /** Removes the named waypoint of a place; whether there was one. */
    public boolean remove(String place, String name) {
        for (int index = 0; index < all.size(); index++) {
            Waypoint each = all.get(index);
            if (each.place.equals(place) && each.name.equalsIgnoreCase(name)) {
                all.remove(index);
                revision++;
                return true;
            }
        }
        return false;
    }

    /** The waypoint of a place and dimension nearest to a point, or null. */
    public Waypoint nearest(String place, int dimension, double x, double y, double z) {
        Waypoint best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Waypoint each : at(place, dimension)) {
            double dx = each.x + 0.5 - x;
            double dy = each.y - y;
            double dz = each.z + 0.5 - z;
            double distance = dx * dx + dy * dy + dz * dz;
            if (distance < bestDistance) {
                bestDistance = distance;
                best = each;
            }
        }
        return best;
    }

    /** The next unused "Waypoint N" name in a place. */
    public String nextName(String place) {
        for (int number = 1; ; number++) {
            String name = "Waypoint " + number;
            boolean taken = false;
            for (Waypoint each : all) {
                if (each.place.equals(place) && each.name.equalsIgnoreCase(name)) {
                    taken = true;
                    break;
                }
            }
            if (!taken) {
                return name;
            }
        }
    }

    /** The colour for the next waypoint of a place. */
    public int nextColour(String place) {
        int count = 0;
        for (Waypoint each : all) {
            if (each.place.equals(place)) {
                count++;
            }
        }
        return COLOURS[count % COLOURS.length];
    }

    /**
     * Runs {@code /fwp} with its arguments, at the player's block position, and
     * returns the line to show in chat.
     */
    public String command(String arguments, String place, int dimension, int x, int y, int z) {
        String[] words = arguments == null ? new String[0] : arguments.trim().split("\\s+");
        String verb = words.length == 0 || words[0].isEmpty() ? "help" : words[0].toLowerCase(Locale.ROOT);
        if ("add".equals(verb)) {
            int at = 1;
            int[] position = {x, y, z};
            StringBuilder name = new StringBuilder();
            // "add <name...> [x y z]": three trailing whole numbers are a position.
            if (words.length >= 4 && whole(words[words.length - 3]) && whole(words[words.length - 2])
                    && whole(words[words.length - 1])) {
                position[0] = Integer.parseInt(words[words.length - 3]);
                position[1] = Integer.parseInt(words[words.length - 2]);
                position[2] = Integer.parseInt(words[words.length - 1]);
                for (; at < words.length - 3; at++) {
                    name.append(name.length() == 0 ? "" : " ").append(words[at]);
                }
            } else {
                for (; at < words.length; at++) {
                    name.append(name.length() == 0 ? "" : " ").append(words[at]);
                }
            }
            String chosen = name.length() == 0 ? nextName(place) : name.toString();
            Waypoint made = add(place, dimension, chosen, position[0], position[1], position[2], nextColour(place));
            return "Waypoint \"" + made.name + "\" at " + made.x + " " + made.y + " " + made.z + ".";
        }
        if ("remove".equals(verb) || "delete".equals(verb)) {
            String name = join(words, 1);
            if (name.isEmpty()) {
                return "Name the waypoint to remove: /fwp remove <name>";
            }
            return remove(place, name) ? "Removed \"" + name + "\"." : "No waypoint called \"" + name + "\" here.";
        }
        if ("list".equals(verb)) {
            List<Waypoint> here = at(place, dimension);
            if (here.isEmpty()) {
                return "No waypoints here. /fwp add <name> makes one where you stand.";
            }
            StringBuilder out = new StringBuilder("Waypoints: ");
            for (int index = 0; index < here.size(); index++) {
                Waypoint each = here.get(index);
                out.append(index == 0 ? "" : ", ").append(each.name).append(" (")
                        .append(each.x).append(' ').append(each.y).append(' ').append(each.z).append(')');
            }
            return out.toString();
        }
        if ("clear".equals(verb)) {
            int before = all.size();
            for (int index = all.size() - 1; index >= 0; index--) {
                if (all.get(index).place.equals(place)) {
                    all.remove(index);
                }
            }
            if (all.size() != before) {
                revision++;
            }
            return "Removed " + (before - all.size()) + " waypoints here.";
        }
        return "/fwp add [name] [x y z], /fwp remove <name>, /fwp list, /fwp clear";
    }

    /** Reads stored lines, skipping any that do not parse. */
    public void load(List<String> lines) {
        all.clear();
        for (String line : lines) {
            String[] fields = line.split("\t");
            if (fields.length != 7) {
                continue;
            }
            try {
                all.add(new Waypoint(fields[0], Integer.parseInt(fields[1]), clean(fields[2]),
                        Integer.parseInt(fields[3]), Integer.parseInt(fields[4]), Integer.parseInt(fields[5]),
                        Integer.parseInt(fields[6], 16)));
            } catch (NumberFormatException unreadable) {
                // A hand edit gone wrong: that line is dropped, the rest are kept.
            }
        }
        revision++;
    }

    public List<String> save() {
        List<String> out = new ArrayList<String>();
        for (Waypoint each : all) {
            out.add(each.place + "\t" + each.dimension + "\t" + each.name + "\t" + each.x + "\t" + each.y + "\t"
                    + each.z + "\t" + String.format(Locale.ROOT, "%06x", each.rgb));
        }
        return out;
    }

    private static String clean(String name) {
        String text = name == null ? "" : name.replace('\t', ' ').replace('\n', ' ').trim();
        if (text.isEmpty()) {
            return "Waypoint";
        }
        return text.length() > 32 ? text.substring(0, 32) : text;
    }

    private static boolean whole(String word) {
        return word.matches("-?\\d{1,8}");
    }

    private static String join(String[] words, int from) {
        StringBuilder out = new StringBuilder();
        for (int index = from; index < words.length; index++) {
            out.append(out.length() == 0 ? "" : " ").append(words[index]);
        }
        return out.toString();
    }
}
