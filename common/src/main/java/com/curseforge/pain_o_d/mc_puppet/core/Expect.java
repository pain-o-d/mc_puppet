package com.curseforge.pain_o_d.mc_puppet.core;

import java.util.Locale;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;

/**
 * Paths into an answer, and what is expected at the end of one.
 *
 * <p>The same small language the scenario runner speaks, here so that the
 * game itself can be asked to wait until an answer looks a certain way:
 * {@code wait_until} looks once a tick, where a test outside could only ask
 * again and again, or sleep and hope.
 *
 * <pre>
 * a.b            a key
 * a[2]  a[-1]    an index, from either end
 * a[key=value]   the first element whose key is that value, ignoring case
 * a[key~=part]   … whose key contains that
 * a#             how many
 * </pre>
 *
 * <p>Expectations: {@code equals}, {@code not}, {@code contains},
 * {@code matches}, {@code gt}, {@code gte}, {@code lt}, {@code lte},
 * {@code exists}. Plain Java, tested without a game.
 */
public final class Expect {

    private Expect() {
    }

    /** The value at a path, or {@code null} where the path leads nowhere. */
    public static JsonElement at(JsonElement root, String path) throws Ops.Refused {
        if (path == null || path.isBlank()) {
            return root;
        }
        JsonElement value = root;
        String rest = path.trim();
        while (!rest.isEmpty()) {
            if (value == null || value.isJsonNull()) {
                return null;
            }
            char first = rest.charAt(0);
            if (first == '.') {
                rest = rest.substring(1);
            } else if (first == '#') {
                value = value.isJsonArray() ? number(value.getAsJsonArray().size())
                        : value.isJsonObject() ? number(value.getAsJsonObject().size()) : null;
                rest = rest.substring(1);
            } else if (first == '[') {
                int close = rest.indexOf(']');
                if (close < 0) {
                    throw new Ops.Refused("unclosed [ in path \"" + path + "\"");
                }
                value = select(value, rest.substring(1, close), path);
                rest = rest.substring(close + 1);
            } else {
                int end = 0;
                while (end < rest.length() && ".[#".indexOf(rest.charAt(end)) < 0) {
                    end++;
                }
                value = value.isJsonObject() ? value.getAsJsonObject().get(rest.substring(0, end)) : null;
                rest = rest.substring(end);
            }
        }
        return value;
    }

    private static JsonElement number(int value) {
        return new com.google.gson.JsonPrimitive(value);
    }

    private static JsonElement select(JsonElement value, String inside, String path) throws Ops.Refused {
        if (!value.isJsonArray()) {
            return null;
        }
        JsonArray array = value.getAsJsonArray();
        String trimmed = inside.trim();
        if (trimmed.matches("-?\\d+")) {
            int index = Integer.parseInt(trimmed);
            if (index < 0) {
                index += array.size();
            }
            return index >= 0 && index < array.size() ? array.get(index) : null;
        }
        boolean partial = inside.contains("~=");
        int at = partial ? inside.indexOf("~=") : inside.indexOf('=');
        if (at <= 0) {
            throw new Ops.Refused("[" + inside + "] in path \"" + path + "\" is neither an index nor key=value");
        }
        String key = inside.substring(0, at).trim();
        String wanted = inside.substring(at + (partial ? 2 : 1)).toLowerCase(Locale.ROOT);
        for (JsonElement each : array) {
            if (!each.isJsonObject() || !each.getAsJsonObject().has(key)
                    || each.getAsJsonObject().get(key).isJsonNull()) {
                continue;
            }
            String held = text(each.getAsJsonObject().get(key)).toLowerCase(Locale.ROOT);
            if (partial ? held.contains(wanted) : held.equals(wanted)) {
                return each;
            }
        }
        return null;
    }

    private static String text(JsonElement value) {
        return value.isJsonPrimitive() ? value.getAsString() : value.toString();
    }

    private static final String[] MATCHERS =
            {"equals", "not", "contains", "matches", "gt", "gte", "lt", "lte", "exists"};

    /** Whether an expectation names anything to check. */
    public static boolean hasMatcher(JsonObject expectation) {
        for (String matcher : MATCHERS) {
            if (expectation.has(matcher)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Checks one expectation against an answer.
     *
     * @return what was wrong, or {@code null}
     */
    public static String check(JsonObject expectation, JsonElement answer) throws Ops.Refused {
        String path = expectation.has("path") && expectation.get("path").isJsonPrimitive()
                ? expectation.get("path").getAsString() : "";
        JsonElement actual = at(answer, path);
        JsonElement shown = actual == null ? JsonNull.INSTANCE : actual;
        String where = path.isEmpty() ? "the answer" : "\"" + path + "\"";
        boolean exists = actual != null && !actual.isJsonNull();

        if (expectation.has("exists") && exists != expectation.get("exists").getAsBoolean()) {
            return where + (exists ? " exists" : " does not exist");
        }
        if (expectation.has("equals") && !shown.equals(expectation.get("equals"))
                && !sameNumber(shown, expectation.get("equals"))) {
            return where + " is " + shown + ", expected " + expectation.get("equals");
        }
        if (expectation.has("not") && (shown.equals(expectation.get("not"))
                || sameNumber(shown, expectation.get("not")))) {
            return where + " is " + shown + ", which it should not be";
        }
        if (expectation.has("contains")) {
            JsonElement wanted = expectation.get("contains");
            boolean held = shown.isJsonArray()
                    ? shown.getAsJsonArray().contains(wanted)
                    : exists && text(shown).toLowerCase(Locale.ROOT).contains(text(wanted).toLowerCase(Locale.ROOT));
            if (!held) {
                return where + " is " + shown + ", which does not contain " + wanted;
            }
        }
        if (expectation.has("matches")) {
            String regex = expectation.get("matches").getAsString();
            try {
                if (!Pattern.compile(regex, Pattern.CASE_INSENSITIVE).matcher(exists ? text(shown) : "").find()) {
                    return where + " is " + shown + ", which does not match /" + regex + "/";
                }
            } catch (PatternSyntaxException broken) {
                throw new Ops.Refused("\"matches\" is not a regular expression: " + broken.getDescription());
            }
        }
        for (String comparison : new String[] {"gt", "gte", "lt", "lte"}) {
            if (!expectation.has(comparison)) {
                continue;
            }
            double bound = expectation.get(comparison).getAsDouble();
            boolean holds = exists && shown.isJsonPrimitive() && shown.getAsJsonPrimitive().isNumber()
                    && switch (comparison) {
                        case "gt" -> shown.getAsDouble() > bound;
                        case "gte" -> shown.getAsDouble() >= bound;
                        case "lt" -> shown.getAsDouble() < bound;
                        default -> shown.getAsDouble() <= bound;
                    };
            if (!holds) {
                return where + " is " + shown + ", expected " + comparison + " " + expectation.get(comparison);
            }
        }
        return null;
    }

    /** 5 and 5.0 are the same number, which Gson's equals does not think. */
    private static boolean sameNumber(JsonElement a, JsonElement b) {
        return a.isJsonPrimitive() && b.isJsonPrimitive()
                && a.getAsJsonPrimitive().isNumber() && b.getAsJsonPrimitive().isNumber()
                && a.getAsDouble() == b.getAsDouble();
    }
}
