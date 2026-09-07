package ir.chobyar.sketch;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic, fail-closed natural-language planner for the production CAD
 * command authority. This class does not execute geometry and does not let an
 * AI invent kernel operations. It only translates a deliberately small,
 * production-tested vocabulary into existing executeCommand(...) strings.
 */
public final class CadCommandPlanner {
    private static final Pattern NUMBER = Pattern.compile("[-+]?\\d+(?:\\.\\d+)?");
    private static final Pattern CLAUSE_SPLIT = Pattern.compile(
            "(?i)\\s+(?:then|and|و|بعد|سپس)\\s+|[;،]+");

    private CadCommandPlanner() {}

    public static final class Plan {
        private final List<String> commands;
        private final String error;

        private Plan(List<String> commands, String error) {
            this.commands = Collections.unmodifiableList(new ArrayList<>(commands));
            this.error = error == null ? "" : error;
        }

        public boolean ok() { return error.isEmpty() && !commands.isEmpty(); }
        public List<String> commands() { return commands; }
        public String error() { return error; }
    }

    private enum Op {
        RECT(new String[]{"rectangle", "rect", "مستطیل"}),
        LINE(new String[]{"line", "خط"}),
        CIRCLE(new String[]{"circle", "دایره"}),
        ARC(new String[]{"arc", "کمان"}),
        EXTRUDE(new String[]{"extrude", "اکسترود"}),
        MOVE(new String[]{"move", "جابجا", "جابهجا", "جابه‌جا", "حرکت"}),
        COPY(new String[]{"copy", "کپی"}),
        OFFSET(new String[]{"offset", "افست"}),
        ROTATE(new String[]{"rotate", "چرخش", "بچرخان"}),
        SCALE(new String[]{"scale", "مقیاس"}),
        MIRROR(new String[]{"mirror", "آینه", "قرینه"}),
        ARRAY(new String[]{"array", "آرایه", "تکثیر"});

        final String[] words;
        Op(String[] words) { this.words = words; }
    }

    public static Plan plan(String raw) {
        String normalized = normalize(raw);
        if (normalized.isEmpty()) return fail("Command is empty");

        String[] clauses = CLAUSE_SPLIT.split(normalized);
        List<String> commands = new ArrayList<>();
        for (String clauseRaw : clauses) {
            String clause = clauseRaw.trim();
            if (clause.isEmpty()) continue;
            Op op = detectSingleOperation(clause);
            if (op == null) return fail("Unsupported or ambiguous CAD instruction: " + clause);
            List<Double> numbers;
            try {
                numbers = numbers(clause, op);
            } catch (IllegalArgumentException e) {
                return fail(e.getMessage());
            }
            String command = build(op, clause, numbers);
            if (command == null) return fail("Invalid arguments for " + op.name());
            commands.add(command);
        }
        return commands.isEmpty() ? fail("No supported CAD operation found") : new Plan(commands, "");
    }

    private static Plan fail(String message) {
        return new Plan(Collections.emptyList(), message);
    }

    private static Op detectSingleOperation(String clause) {
        Op found = null;
        for (Op op : Op.values()) {
            if (!containsAny(clause, op.words)) continue;
            if (found != null) return null;
            found = op;
        }
        return found;
    }

    private static boolean containsAny(String clause, String[] words) {
        String lower = clause.toLowerCase(Locale.US);
        for (String word : words) {
            if (isAsciiWord(word)) {
                Pattern p = Pattern.compile("(?i)(?:^|[^a-z0-9_])" + Pattern.quote(word) + "(?:$|[^a-z0-9_])");
                if (p.matcher(lower).find()) return true;
            } else if (lower.contains(word.toLowerCase(Locale.US))) return true;
        }
        return false;
    }

    private static boolean isAsciiWord(String s) {
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) > 127) return false;
        return true;
    }

    private static List<Double> numbers(String clause, Op op) {
        boolean cm = containsAny(clause, new String[]{"cm", "سانت", "سانتی"});
        boolean mm = containsAny(clause, new String[]{"mm", "میلی"});
        if (cm && mm) throw new IllegalArgumentException("Mixed units in one instruction are not supported yet");
        if (cm && (op == Op.ROTATE || op == Op.SCALE || op == Op.ARRAY)) {
            throw new IllegalArgumentException("Centimeter units are not valid for this operation");
        }
        double factor = cm ? 10.0 : 1.0;
        List<Double> out = new ArrayList<>();
        Matcher m = NUMBER.matcher(clause);
        while (m.find()) {
            double value = Double.parseDouble(m.group());
            if (!Double.isFinite(value)) throw new IllegalArgumentException("Non-finite numeric input");
            out.add(value * factor);
        }
        return out;
    }

    private static String build(Op op, String clause, List<Double> n) {
        switch (op) {
            case RECT:
                if (n.size() == 2 && positive(n.get(0)) && positive(n.get(1)))
                    return "RECT 0 0 " + f(n.get(0)) + " " + f(n.get(1));
                if (n.size() == 4 && positive(n.get(2)) && positive(n.get(3)))
                    return "RECT " + join(n);
                return null;
            case LINE:
                if (n.size() != 4) return null;
                if (samePoint(n.get(0), n.get(1), n.get(2), n.get(3))) return null;
                return "LINE " + join(n);
            case CIRCLE:
                if (n.size() == 1 && positive(n.get(0))) return "CIRCLE 0 0 " + f(n.get(0));
                if (n.size() == 3 && positive(n.get(2))) return "CIRCLE " + join(n);
                return null;
            case ARC:
                if (n.size() != 5 || !positive(n.get(2))) return null;
                return "ARC " + join(n);
            case EXTRUDE:
                if (n.size() != 1 || nearZero(n.get(0))) return null;
                return "EXTRUDE " + f(n.get(0));
            case MOVE:
                if (n.size() != 2 || (nearZero(n.get(0)) && nearZero(n.get(1)))) return null;
                return "MOVE " + join(n);
            case COPY:
                if (n.size() != 2 || (nearZero(n.get(0)) && nearZero(n.get(1)))) return null;
                return "COPY " + join(n);
            case OFFSET:
                if (n.size() != 1 || nearZero(n.get(0))) return null;
                return "OFFSET " + f(n.get(0));
            case ROTATE:
                if (n.size() != 1 || nearZero(n.get(0))) return null;
                return "ROTATE " + f(n.get(0));
            case SCALE:
                if (n.size() != 1 || !positive(n.get(0))) return null;
                return "SCALE " + f(n.get(0));
            case MIRROR:
                String axis = mirrorAxis(clause);
                if (axis == null || n.size() > 1) return null;
                return "MIRROR " + axis + " " + (n.isEmpty() ? "0" : f(n.get(0)));
            case ARRAY:
                if (n.size() != 3) return null;
                int count = (int) Math.rint(n.get(0));
                if (Math.abs(n.get(0) - count) > 1e-9 || count < 2) return null;
                if (nearZero(n.get(1)) && nearZero(n.get(2))) return null;
                return "ARRAY " + count + " " + f(n.get(1)) + " " + f(n.get(2));
            default:
                return null;
        }
    }

    private static String mirrorAxis(String clause) {
        String s = clause.toLowerCase(Locale.US);
        if (Pattern.compile("(?:^|[^a-z0-9])x(?:$|[^a-z0-9])", Pattern.CASE_INSENSITIVE).matcher(s).find()
                || s.contains("محور x")) return "X";
        if (Pattern.compile("(?:^|[^a-z0-9])y(?:$|[^a-z0-9])", Pattern.CASE_INSENSITIVE).matcher(s).find()
                || s.contains("محور y")) return "Y";
        return null;
    }

    private static boolean samePoint(double x1, double y1, double x2, double y2) {
        return Math.abs(x1 - x2) < 1e-9 && Math.abs(y1 - y2) < 1e-9;
    }

    private static boolean nearZero(double v) { return Math.abs(v) < 1e-9; }
    private static boolean positive(double v) { return v > 1e-9; }

    private static String join(List<Double> values) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) b.append(' ');
            b.append(f(values.get(i)));
        }
        return b.toString();
    }

    private static String f(double v) {
        return BigDecimal.valueOf(v).stripTrailingZeros().toPlainString();
    }

    private static String normalize(String raw) {
        if (raw == null) return "";
        StringBuilder b = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c >= '۰' && c <= '۹') c = (char) ('0' + (c - '۰'));
            else if (c >= '٠' && c <= '٩') c = (char) ('0' + (c - '٠'));
            else if (c == '٫') c = '.';
            else if (c == '٬') continue;
            b.append(c);
        }
        return b.toString().trim().replaceAll("\\s+", " ");
    }
}
