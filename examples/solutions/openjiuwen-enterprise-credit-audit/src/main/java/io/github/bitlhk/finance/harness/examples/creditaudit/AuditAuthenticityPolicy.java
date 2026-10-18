package io.github.bitlhk.finance.harness.examples.creditaudit;

import io.github.bitlhk.finance.harness.core.CapabilityResultV1;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** Bounded authenticity language policy; not a general language or Markdown parser. */
final class AuditAuthenticityPolicy {
    static final String CODE = "MODEL_EXPLANATION_AUTHENTICITY_STATUS_CONFLICT";
    private static final List<String> CLAIMS = List.of("未通过真实性验证", "真实性验证失败",
            "真实性核验失败", "材料虚假", "真实性不合格");
    private static final List<String> BOUNDARIES = List.of("\u0001", "，", ",", "；", ";", "。", ".",
            "！", "!", "？", "?", "但", "然而", "却");
    private static final Pattern DENIAL = Pattern.compile(
            "不能|不得|不应|不可|无法|不构成|不代表|不意味着|不等于|没有证据证明");
    private static final Pattern DOUBLE_NEGATIVE = Pattern.compile(
            "(?s).*(并非|不是|不能|不得|不应|无法|没有|并不)(?:说|认为)?\\s*$");
    private static final Pattern VERB = Pattern.compile(
            "(?:据此|直接|明确)?\\s*(?:说|声称|认定|判断|说明|证明|解释为|解读为|视为|当作)?\\s*");
    private static final Pattern CLAIM_LIST = Pattern.compile(
            "(?:核验失败|真实性验证失败|未通过真实性验证|真实性核验失败|真实性不合格|材料虚假|"
            + "结论|的|或|以及|和|也|[\\s“”\"'：:])*");
    private static final Pattern ATTRIBUTION = Pattern.compile(
            "(?s).*(引用|原话|声称|表示|(?:有人|外部人员|报告)?说)[：:“”\"']?\\s*$");
    private static final Pattern REJECT_QUOTE = Pattern.compile(
            "^[”\"']?[，,]?\\s*(?:但|然而)?(?:当前尚未核验[，,])?"
            + "(?:不能采信|不采信|不认可|不得据此|仅为转述|不作为结论)(?:该说法|该结论|此说法|此结论)?[。！!？?，,；;\\s]*$");

    private AuditAuthenticityPolicy() { }

    static void requireAllowed(String raw, List<CapabilityResultV1> facts) {
        View view = View.of(raw);
        for (Claim claim : claims(view.text())) {
            int boundary = lastBoundary(view.text(), claim.start());
            String before = view.text().substring(boundary, claim.start());
            if (negated(view, boundary, before) || rejectedQuote(view, claim, before)) continue;
            if (supportsFailure(view, claim, boundary, facts)) continue;
            throw new Rejected(view.start(claim.start()), view.end(claim.end()),
                    "SCOPE_OR_BOUND_FACT_NOT_ESTABLISHED");
        }
    }

    record Claim(int start, int end, String phrase) { }

    private static List<Claim> claims(String text) {
        List<Claim> claims = new ArrayList<>();
        for (String phrase : CLAIMS) {
            int start = 0;
            while ((start = text.indexOf(phrase, start)) >= 0) {
                claims.add(new Claim(start, start + phrase.length(), phrase));
                start += phrase.length();
            }
        }
        claims.sort(Comparator.comparingInt(Claim::start));
        return claims;
    }

    private static boolean negated(View view, int boundary, String before) {
        var denial = DENIAL.matcher(before);
        while (denial.find()) {
            // Formatting may wrap a complete operator, never synthesize one from fragments.
            if (!view.contiguous(boundary + denial.start(), boundary + denial.end())) continue;
            if (DOUBLE_NEGATIVE.matcher(before.substring(0, denial.start())).matches()) continue;
            String tail = before.substring(denial.end());
            var verb = VERB.matcher(tail);
            if (!verb.lookingAt()) continue;
            if (CLAIM_LIST.matcher(tail.substring(verb.end())).matches()) return true;
        }
        return false;
    }

    private static boolean rejectedQuote(View view, Claim claim, String before) {
        if (!ATTRIBUTION.matcher(before).matches()) return false;
        String text = view.text();
        int end = claim.end();
        while (end < text.length() && "\u0001。.!！？?；;".indexOf(text.charAt(end)) < 0) end++;
        if (end < text.length() && text.charAt(end) != '\u0001') end++;
        String after = text.substring(claim.end(), end);
        if (!REJECT_QUOTE.matcher(after).matches()) return false;
        var rejection = Pattern.compile("不能采信|不采信|不认可|不得据此|仅为转述|不作为结论").matcher(after);
        return rejection.find() && view.contiguous(claim.end() + rejection.start(), claim.end() + rejection.end());
    }

    private static boolean supportsFailure(View view, Claim claim, int boundary, List<CapabilityResultV1> facts) {
        // An explicit FAILED verification does not establish that material is false.
        if (claim.phrase().equals("材料虚假")) return false;
        String subject = view.text().substring(boundary, claim.start());
        if (ATTRIBUTION.matcher(subject).matches()) return false;
        List<CapabilityResultV1> bound = facts.stream().filter(fact -> {
            Object check = fact.data().get("checkId"), object = fact.data().get("materialSetRef");
            return check instanceof String c && !c.isBlank() && object instanceof String o && !o.isBlank()
                    && hasReference(subject, c) && hasReference(subject, o);
        }).toList();
        if (bound.size() == 1) return explicitlyFailed(bound.get(0)) && !namesOtherBinding(subject, bound.get(0));
        // A single frozen result is an unambiguous implicit scope, but must itself carry a binding.
        if (facts.size() != 1 || !view.text().substring(0, claim.start()).isBlank()) return false;
        var fact = facts.get(0);
        return fact.data().get("checkId") instanceof String c && !c.isBlank()
                && fact.data().get("materialSetRef") instanceof String o && !o.isBlank() && explicitlyFailed(fact);
    }

    private static boolean namesOtherBinding(String subject, CapabilityResultV1 fact) {
        var refs = Pattern.compile("materials-[A-Za-z0-9_-]+").matcher(subject);
        while (refs.find()) if (!refs.group().equals(fact.data().get("materialSetRef"))) return true;
        for (String check : List.of("financial-statement-equation", "application-contract-consistency"))
            if (subject.contains(check) && !check.equals(fact.data().get("checkId"))) return true;
        return false;
    }

    private static boolean hasReference(String text, String reference) {
        return Pattern.compile("(?<![A-Za-z0-9_-])" + Pattern.quote(reference) + "(?![A-Za-z0-9_-])")
                .matcher(text).find();
    }

    private static boolean explicitlyFailed(CapabilityResultV1 fact) {
        if (fact.status() != CapabilityResultV1.Status.COMPLETED || !"FAILED".equals(fact.data().get("authenticityStatus"))) return false;
        Object assessment = fact.data().get("authenticityAssessment");
        return !(assessment instanceof Map<?, ?> map) || "FAILED".equals(map.get("status"));
    }

    private static int lastBoundary(String text, int before) {
        int boundary = 0;
        for (String marker : BOUNDARIES) {
            int index = text.lastIndexOf(marker, before - 1);
            if (index >= 0) boundary = Math.max(boundary, index + marker.length());
        }
        return boundary;
    }

    static final class Rejected extends IllegalArgumentException {
        private final int start, end;
        private final String reason;
        Rejected(int start, int end, String reason) {
            super(CODE); this.start = start; this.end = end; this.reason = reason;
        }
        Map<String, Object> diagnostic() {
            return Map.of("rule", "AUDIT_AUTHENTICITY_SCOPE_V2", "start", start, "end", end,
                    "offsetUnit", "UTF16", "reason", reason);
        }
    }

    /** A text projection with original UTF-16 positions; original evidence is never rewritten. */
    record View(String text, List<Integer> positions) {
        int start(int offset) { return positions.get(offset); }
        int end(int offset) { return positions.get(offset - 1) + 1; }
        boolean contiguous(int start, int end) {
            for (int i = start + 1; i < end; i++) if (positions.get(i) != positions.get(i - 1) + 1) return false;
            return true;
        }
        static View of(String raw) {
            boolean[] skip = inlineMarkers(raw);
            var text = new StringBuilder(); var positions = new ArrayList<Integer>();
            for (int i = 0; i < raw.length(); i++) {
                if (skip[i]) continue;
                char ch = raw.charAt(i);
                if (ch == '\r' && i + 1 < raw.length() && raw.charAt(i + 1) == '\n') continue;
                if (ch == '\n' || ch == '\r') ch = blockBreak(raw, i) ? '\u0001' : ' ';
                else if (Character.isWhitespace(ch) || Character.isSpaceChar(ch)) ch = ' ';
                text.append(ch); positions.add(i);
            }
            return new View(text.toString(), List.copyOf(positions));
        }
        private static boolean blockBreak(String raw, int newline) {
            int previous = newline - 1;
            if (previous >= 0 && raw.charAt(previous) == '\r') previous--;
            if (previous >= 0 && raw.charAt(previous) == '\\') return true;
            if (previous > 0 && raw.charAt(previous) == ' ' && raw.charAt(previous - 1) == ' ') return true;
            int next = newline + 1;
            while (next < raw.length() && (raw.charAt(next) == ' ' || raw.charAt(next) == '\t' || raw.charAt(next) == '\r')) next++;
            if (next == raw.length() || raw.charAt(next) == '\n') return true;
            String rest = raw.substring(next, raw.indexOf('\n', next) < 0 ? raw.length() : raw.indexOf('\n', next));
            return rest.matches("(?:#{1,6}\\s|>\\s?|[-+*]\\s|[0-9]+[.)]\\s|\\|).*");
        }
        private static boolean[] inlineMarkers(String raw) {
            boolean[] skip = new boolean[raw.length()];
            for (int i = 0; i < raw.length(); i++) {
                if (skip[i] || (i > 0 && raw.charAt(i - 1) == '\\')) continue;
                String marker = null;
                for (String candidate : List.of("**", "__", "*", "_", "`"))
                    if (raw.startsWith(candidate, i)) { marker = candidate; break; }
                if (marker == null) continue;
                int content = i + marker.length(), close = raw.indexOf(marker, content);
                if (close <= content || Character.isWhitespace(raw.charAt(content)) || Character.isWhitespace(raw.charAt(close - 1))) continue;
                if (raw.substring(content, close).indexOf('\n') >= 0 || raw.charAt(close - 1) == '\\') continue;
                for (int j = 0; j < marker.length(); j++) { skip[i + j] = true; skip[close + j] = true; }
                i += marker.length() - 1;
            }
            return skip;
        }
    }
}
