package io.github.bitlhk.finance.harness.integrations.customermanager;

import java.util.*;

/** Session-local task continuity. It stores references and user inputs, never business records or authority. */
final class WealthServiceJourney {
    private String customerRef;
    private final LinkedHashSet<String> productRefs = new LinkedHashSet<>();
    private final Map<String,Object> parameters = new LinkedHashMap<>();
    private final LinkedHashSet<String> evidenceRefs = new LinkedHashSet<>();
    private final LinkedHashSet<String> pendingItems = new LinkedHashSet<>();
    private String reportRef;
    private Integer reportRevision;
    private String stage = "START";

    synchronized void reset() {
        customerRef = null; productRefs.clear(); parameters.clear(); evidenceRefs.clear(); pendingItems.clear();
        reportRef = null; reportRevision = null; stage = "START";
    }

    synchronized void selectCustomer(String value) {
        if (value == null || value.isBlank()) return;
        if (!value.equals(customerRef)) {
            customerRef = value;
            productRefs.clear(); parameters.clear(); evidenceRefs.clear(); pendingItems.clear();
            reportRef = null; reportRevision = null;
            stage = "CUSTOMER_SELECTED";
        }
    }

    synchronized String customerRef() { return customerRef; }

    synchronized void selectProducts(Collection<String> values, boolean comparison) {
        var clean = values.stream().filter(Objects::nonNull).filter(value -> !value.isBlank()).toList();
        if (clean.isEmpty()) return;
        if (!comparison) productRefs.clear();
        productRefs.addAll(clean);
        while (productRefs.size() > 2) productRefs.remove(productRefs.iterator().next());
        stage = comparison ? "PRODUCTS_COMPARED" : "PRODUCT_SELECTED";
    }

    synchronized List<String> productRefs() { return List.copyOf(productRefs); }

    synchronized void parameters(Map<String,Object> values) {
        for (String key : List.of("amount", "horizonMonths", "channel"))
            if (values.containsKey(key)) parameters.put(key, values.get(key));
    }

    synchronized Map<String,Object> parameters() { return Map.copyOf(parameters); }

    synchronized void evidence(Collection<String> values) {
        values.stream().filter(Objects::nonNull).filter(value -> !value.isBlank()).forEach(evidenceRefs::add);
        while (evidenceRefs.size() > 24) evidenceRefs.remove(evidenceRefs.iterator().next());
    }

    synchronized void pending(Collection<String> values) {
        pendingItems.clear();
        values.stream().filter(Objects::nonNull).filter(value -> !value.isBlank()).forEach(pendingItems::add);
    }

    synchronized void report(String id, int revision, Collection<String> products) {
        reportRef = Objects.requireNonNull(id); reportRevision = revision;
        selectProducts(products, products.size() > 1); stage = "ADVICE_READY";
    }

    synchronized boolean hasReport() { return reportRef != null && reportRevision != null; }
    synchronized String reportRef() { return reportRef; }
    synchronized int reportRevision() { return reportRevision == null ? -1 : reportRevision; }

    synchronized boolean productContinuation(String query) {
        if (productRefs.isEmpty() || query == null) return false;
        return query.matches(".*(?:这款|这个产品|这两款|它们|哪个更|流动性更好|费用更低|为什么选).*?");
    }

    synchronized boolean reportFollowup(String query) {
        if (!hasReport() || query == null) return false;
        return query.matches(".*(?:为什么(?:选|推荐)|推荐理由|排除理由|解释.*候选|沟通(?:的)?话术|怎么和客户说|如何介绍).*?");
    }

    synchronized Map<String,Object> projection() {
        var value = new LinkedHashMap<String,Object>();
        value.put("schemaVersion", "wealth-service-journey.v0.1");
        value.put("authority", "NONE"); value.put("stage", stage); value.put("customerRef", customerRef);
        value.put("productRefs", List.copyOf(productRefs)); value.put("parameters", Map.copyOf(parameters));
        value.put("evidenceRefs", List.copyOf(evidenceRefs)); value.put("pendingItems", List.copyOf(pendingItems));
        if (reportRef != null) value.put("report", Map.of("reportRef", reportRef, "revision", reportRevision));
        return Collections.unmodifiableMap(value);
    }
}
