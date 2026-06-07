import java.util.*;

sealed interface JsonValue {
    record JsonObject(Map<String, JsonValue> map) implements JsonValue {}
    record JsonArray(List<JsonValue> list) implements JsonValue {}
    record JsonString(String value) implements JsonValue {}
    record JsonNumber(double value) implements JsonValue {
        public long longValue() { return (long) value; }
        public int intValue() { return (int) value; }
    }
    record JsonBoolean(boolean value) implements JsonValue {}
    record JsonNull() implements JsonValue {}
}

class JsonParser {
    private final String src;
    private int pos;

    JsonParser(String src) {
        this.src = src;
        this.pos = 0;
    }

    JsonValue parse() {
        skipWhitespace();
        JsonValue val = parseValue();
        return val;
    }

    private JsonValue parseValue() {
        skipWhitespace();
        if (pos >= src.length()) throw new RuntimeException("Unexpected end of input");
        char c = src.charAt(pos);
        return switch (c) {
            case '{' -> parseObject();
            case '[' -> parseArray();
            case '"' -> new JsonValue.JsonString(parseString());
            case 't', 'f' -> new JsonValue.JsonBoolean(parseBoolean());
            case 'n' -> { parseNull(); yield new JsonValue.JsonNull(); }
            default -> {
                if (c == '-' || (c >= '0' && c <= '9')) {
                    yield new JsonValue.JsonNumber(parseNumber());
                }
                throw new RuntimeException("Unexpected character: " + c + " at position " + pos);
            }
        };
    }

    private JsonValue.JsonObject parseObject() {
        Map<String, JsonValue> map = new LinkedHashMap<>();
        pos++; // skip {
        skipWhitespace();
        if (pos < src.length() && src.charAt(pos) == '}') {
            pos++;
            return new JsonValue.JsonObject(map);
        }
        while (pos < src.length()) {
            skipWhitespace();
            if (src.charAt(pos) == '"') {
                String key = parseString();
                skipWhitespace();
                expect(':');
                skipWhitespace();
                JsonValue val = parseValue();
                map.put(key, val);
                skipWhitespace();
                if (src.charAt(pos) == ',') {
                    pos++;
                } else if (src.charAt(pos) == '}') {
                    pos++;
                    break;
                } else {
                    throw new RuntimeException("Expected ',' or '}' in object at " + pos);
                }
            } else {
                throw new RuntimeException("Expected '\"' in object at " + pos);
            }
        }
        return new JsonValue.JsonObject(map);
    }

    private JsonValue.JsonArray parseArray() {
        List<JsonValue> list = new ArrayList<>();
        pos++; // skip [
        skipWhitespace();
        if (pos < src.length() && src.charAt(pos) == ']') {
            pos++;
            return new JsonValue.JsonArray(list);
        }
        while (pos < src.length()) {
            list.add(parseValue());
            skipWhitespace();
            if (src.charAt(pos) == ',') {
                pos++;
            } else if (src.charAt(pos) == ']') {
                pos++;
                break;
            } else {
                throw new RuntimeException("Expected ',' or ']' in array at " + pos);
            }
        }
        return new JsonValue.JsonArray(list);
    }

    private String parseString() {
        pos++; // skip opening "
        StringBuilder sb = new StringBuilder();
        while (pos < src.length()) {
            char c = src.charAt(pos);
            if (c == '"') {
                pos++;
                return sb.toString();
            }
            if (c == '\\') {
                pos++;
                if (pos >= src.length()) throw new RuntimeException("Unexpected end in string escape");
                char escaped = src.charAt(pos);
                sb.append(switch (escaped) {
                    case '"' -> '"';
                    case '\\' -> '\\';
                    case '/' -> '/';
                    case 'n' -> '\n';
                    case 'r' -> '\r';
                    case 't' -> '\t';
                    case 'b' -> '\b';
                    case 'f' -> '\f';
                    case 'u' -> {
                        String hex = src.substring(pos + 1, Math.min(pos + 5, src.length()));
                        pos += 4;
                        yield (char) Integer.parseInt(hex, 16);
                    }
                    default -> throw new RuntimeException("Invalid escape: \\" + escaped);
                });
                pos++;
            } else {
                sb.append(c);
                pos++;
            }
        }
        throw new RuntimeException("Unterminated string");
    }

    private double parseNumber() {
        int start = pos;
        if (src.charAt(pos) == '-') pos++;
        while (pos < src.length() && src.charAt(pos) >= '0' && src.charAt(pos) <= '9') pos++;
        if (pos < src.length() && src.charAt(pos) == '.') {
            pos++;
            while (pos < src.length() && src.charAt(pos) >= '0' && src.charAt(pos) <= '9') pos++;
        }
        if (pos < src.length() && (src.charAt(pos) == 'e' || src.charAt(pos) == 'E')) {
            pos++;
            if (pos < src.length() && (src.charAt(pos) == '+' || src.charAt(pos) == '-')) pos++;
            while (pos < src.length() && src.charAt(pos) >= '0' && src.charAt(pos) <= '9') pos++;
        }
        return Double.parseDouble(src.substring(start, pos));
    }

    private boolean parseBoolean() {
        if (src.startsWith("true", pos)) {
            pos += 4;
            return true;
        } else if (src.startsWith("false", pos)) {
            pos += 5;
            return false;
        }
        throw new RuntimeException("Expected boolean at " + pos);
    }

    private void parseNull() {
        if (src.startsWith("null", pos)) {
            pos += 4;
        } else {
            throw new RuntimeException("Expected null at " + pos);
        }
    }

    private void expect(char c) {
        if (pos >= src.length() || src.charAt(pos) != c)
            throw new RuntimeException("Expected '" + c + "' at " + pos);
        pos++;
    }

    private void skipWhitespace() {
        while (pos < src.length() && Character.isWhitespace(src.charAt(pos))) pos++;
    }

    // --- convenience helpers ---
    static String optString(JsonValue val, String key) {
        if (val instanceof JsonValue.JsonObject obj) {
            JsonValue v = obj.map().get(key);
            if (v instanceof JsonValue.JsonString s) return s.value();
        }
        return "";
    }

    static String optString(JsonValue val, String key, String def) {
        String s = optString(val, key);
        return s.isEmpty() ? def : s;
    }

    static JsonValue.JsonArray optArray(JsonValue val, String key) {
        if (val instanceof JsonValue.JsonObject obj) {
            JsonValue v = obj.map().get(key);
            if (v instanceof JsonValue.JsonArray a) return a;
        }
        return new JsonValue.JsonArray(List.of());
    }
}
