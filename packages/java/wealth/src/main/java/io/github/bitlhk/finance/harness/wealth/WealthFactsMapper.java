package io.github.bitlhk.finance.harness.wealth;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.bitlhk.finance.harness.core.CapabilityResultV1;
import io.github.bitlhk.finance.harness.core.HarnessJson;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static io.github.bitlhk.finance.harness.wealth.WealthFactsV1.*;

/** All source-specific field knowledge is confined here, before model context creation. */
public final class WealthFactsMapper {
    public enum Format { MOBILE, EA }
    private WealthFactsMapper() { }

    public static WealthFactsV1 map(Format format, CapabilityResultV1 result, String authorizedSubject,
                                   WealthSourceSemantics semantics, SourceKind kind, Clock clock) {
        return map(format, result, authorizedSubject, semantics, kind, clock, clock);
    }

    /** Business time may be fixed for replay; observation time must describe the actual invocation. */
    public static WealthFactsV1 map(Format format, CapabilityResultV1 result, String authorizedSubject,
                                   WealthSourceSemantics semantics, SourceKind kind, Clock businessClock, Clock observationClock) {
        if (result.status() != CapabilityResultV1.Status.COMPLETED)
            throw new IllegalArgumentException("Only completed authorized queries may be mapped");
        JsonNode root = HarnessJson.mapper().valueToTree(result.data());
        List<Gap> gaps = new ArrayList<>();
        JsonNode assets; JsonNode risk; String valuation; boolean unavailable;
        if (format == Format.MOBILE) {
            requireObject(root.path("wealthAggregate"));
            assets = root.path("wealthAggregate").path("overview");
            risk = root.path("riskAssessment");
            valuation = string(assets.path("asOf"));
            if (valuation == null) valuation = string(root.path("wealthAggregate").path("dataQuality").path("valuationAt"));
            unavailable = false;
            for (JsonNode snapshot : root.path("sourceSnapshots")) {
                if ("RISK_ASSESSMENT".equals(snapshot.path("section").asText())
                        && "UNAVAILABLE".equals(snapshot.path("status").asText()) && risk.isEmpty()) unavailable = true;
            }
        } else {
            JsonNode previsit = root.path("previsitContext"); requireObject(previsit);
            String status = string(previsit.path("status"));
            if (!Set.of("ready", "degraded").contains(status == null ? "" : status))
                throw new IllegalArgumentException("Unsupported EA result state");
            JsonNode customer = previsit.path("customer");
            if (!customer.isMissingNode() && !customer.isNull()) {
                requireObject(customer);
                if (!authorizedSubject.equals(string(customer.path("customerId"))))
                    throw new SecurityException("Source customer mismatch");
            }
            String evidenceSubject = string(previsit.path("evidence").path("customerId"));
            if (evidenceSubject != null && !authorizedSubject.equals(evidenceSubject)) throw new SecurityException("Evidence customer mismatch");
            assets = customer.path("portfolio").path("summary");
            risk = customer;
            valuation = semantics.dataTimeIsValuationTime()
                    ? string(previsit.path("evidence").path("portfolioDataAsOf")) : null;
            unavailable = "degraded".equals(status) && (customer.isMissingNode() || customer.isNull());
        }
        if (!assets.isMissingNode() && !assets.isNull()) requireObject(assets);
        Assets mappedAssets = assets(assets, valuation, semantics, gaps, format == Format.EA && unavailable);
        Risk mappedRisk = risk(risk, format, semantics, gaps, unavailable, businessClock);
        return new WealthFactsV1(VERSION, new Subject(semantics.namespace(), authorizedSubject), mappedAssets, mappedRisk, gaps,
                new Source(result.sourceSystem(), semantics.contract(), semantics.basisReference(), kind, observationClock.instant(), digest(root)));
    }

    private static Assets assets(JsonNode source, String valuation, WealthSourceSemantics semantics, List<Gap> gaps, boolean unavailable) {
        List<String> missing = new ArrayList<>();
        JsonNode missingNode = source.path("missingSourceSystems");
        if (!missingNode.isMissingNode()) {
            if (!missingNode.isArray()) throw new IllegalArgumentException("Invalid missing sources");
            missingNode.forEach(item -> { if (!item.isTextual() || item.asText().isBlank()) throw new IllegalArgumentException("Invalid missing source"); missing.add(item.asText()); });
        }
        if (!missing.isEmpty()) gaps.add(new Gap("assets.coverage", Availability.NOT_COVERED, "SOURCE_COVERAGE_PARTIAL"));
        JsonNode amount = source.path("totalAssets");
        if (amount.isMissingNode() || amount.isNull()) {
            Availability state = unavailable ? Availability.UNAVAILABLE : Availability.MISSING;
            gaps.add(new Gap("assets", state, "ASSETS_NOT_RETURNED"));
            return new Assets(state, List.of(), missing);
        }
        String currency = string(source.path("currency"));
        if (currency != null && semantics.currency() != null && !currency.equals(semantics.currency())) {
            gaps.add(new Gap("assets.currency", Availability.CONFLICT, "SOURCE_CURRENCY_CONFLICT"));
            return new Assets(Availability.CONFLICT, List.of(), missing);
        }
        if (currency == null) currency = semantics.currency();
        if (currency == null) {
            gaps.add(new Gap("assets.currency", Availability.MISSING, "CURRENCY_NOT_ESTABLISHED"));
            return new Assets(Availability.MISSING, List.of(), missing);
        }
        if (!amount.isNumber() && !amount.isTextual()) throw new IllegalArgumentException("Invalid asset amount");
        BigDecimal decimal = new BigDecimal(amount.asText());
        if (decimal.signum() < 0 || decimal.scale() > 8 || (long) decimal.precision() - decimal.scale() > 18)
            throw new IllegalArgumentException("Asset amount exceeds exact contract bounds");
        String exact = decimal.toPlainString();
        Money money = new Money(exact, currency);
        String scope = semantics.assetScope();
        if (scope == null) { scope = "SOURCE_REPORTED_UNSPECIFIED"; gaps.add(new Gap("assets.scope", Availability.MISSING, "ASSET_SCOPE_NOT_ESTABLISHED")); }
        Instant at = valuation == null ? null : OffsetDateTime.parse(valuation).toInstant();
        if (at == null) gaps.add(new Gap("assets.valuationAt", Availability.MISSING, "VALUATION_TIME_NOT_ESTABLISHED"));
        JsonNode stale = source.path("stale");
        if (!stale.isMissingNode() && !stale.isBoolean()) throw new IllegalArgumentException("Invalid stale marker");
        Availability state = stale.asBoolean(false) ? Availability.EXPIRED : Availability.AVAILABLE;
        if (state == Availability.EXPIRED) gaps.add(new Gap("assets", state, "SOURCE_MARKED_STALE"));
        return new Assets(state, List.of(new AssetAmount(money, scope, at)), missing);
    }

    private static Risk risk(JsonNode source, Format format, WealthSourceSemantics semantics, List<Gap> gaps, boolean unavailable, Clock clock) {
        if (!source.isMissingNode() && !source.isNull()) requireObject(source);
        String level = string(source.path("riskLevel"));
        if (level == null) {
            Availability state = unavailable ? Availability.UNAVAILABLE : Availability.MISSING;
            gaps.add(new Gap("risk", state, "RISK_NOT_RETURNED"));
            return new Risk(state, null, null, null, null, null, null);
        }
        String version = format == Format.MOBILE ? string(source.path("assessmentVersion")) : semantics.riskVersion();
        String assessed = format == Format.MOBILE ? string(source.path("assessedOn")) : null;
        String expires = string(source.path(format == Format.MOBILE ? "expiresOn" : "assessmentExpiresAt"));
        String zone = semantics.dateZone() == null ? null : semantics.dateZone().getId();
        if (semantics.riskScheme() == null) gaps.add(new Gap("risk.scheme", Availability.MISSING, "RISK_SCHEME_NOT_ESTABLISHED"));
        if (version == null) gaps.add(new Gap("risk.assessmentVersion", Availability.MISSING, "RISK_VERSION_NOT_ESTABLISHED"));
        Availability state = Availability.AVAILABLE;
        if (expires == null) gaps.add(new Gap("risk.expiresOn", Availability.MISSING, "RISK_VALIDITY_NOT_RETURNED"));
        else {
            validateDate(expires);
            Instant deadline = null;
            if (expires.length() == 10) {
                if (zone == null) gaps.add(new Gap("risk.dateZone", Availability.MISSING, "SOURCE_DATE_ZONE_UNKNOWN"));
                else deadline = LocalDate.parse(expires).plusDays(1).atStartOfDay(semantics.dateZone()).toInstant();
            } else deadline = OffsetDateTime.parse(expires).toInstant();
            if (deadline != null && !clock.instant().isBefore(deadline)) state = Availability.EXPIRED;
        }
        if ("EXPIRED".equals(string(source.path("status")))) state = Availability.EXPIRED;
        if (state == Availability.EXPIRED) gaps.add(new Gap("risk", state, "RISK_ASSESSMENT_EXPIRED"));
        return new Risk(state, level, semantics.riskScheme(), version, assessed, expires, zone);
    }

    private static String string(JsonNode node) {
        if (node.isMissingNode() || node.isNull()) return null;
        if (!node.isTextual() || node.asText().isBlank()) throw new IllegalArgumentException("Invalid text field");
        return node.asText();
    }
    private static void requireObject(JsonNode node) { if (!node.isObject()) throw new IllegalArgumentException("Invalid source object"); }
    private static String digest(JsonNode node) {
        try { return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(HarnessJson.mapper().writeValueAsBytes(node))); }
        catch (Exception exception) { throw new IllegalArgumentException("Cannot digest source", exception); }
    }
}
