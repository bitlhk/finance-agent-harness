package io.github.bitlhk.finance.builder.candidate;

import io.github.bitlhk.finance.harness.examples.wealthkit.WealthKitApplication;
import io.github.bitlhk.finance.harness.wealth.WealthJson;
import java.nio.file.Path;
import java.util.HashSet;

/** Thin generated entrypoint; all financial execution and assertions remain in Harness. */
public final class ReadOnlyCandidate {
    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("Expected asset root and output directory");
        Path root = Path.of(args[0]);
        Path output = Path.of(args[1]);
        var cases = WealthJson.mapper().readTree(root.resolve("cases.json").toFile());
        if (!cases.isArray() || cases.isEmpty()) throw new IllegalArgumentException("Non-empty cases required");
        var seen = new HashSet<String>();
        for (var value : cases) {
            var config = WealthJson.mapper().treeToValue(value, WealthKitApplication.Configuration.class);
            String name = config.binding() + "-" + config.scenario();
            if (!seen.add(name)) throw new IllegalArgumentException("Duplicate case");
            WealthKitApplication.run(root, config, output.resolve(name));
            System.out.println("CONTROLLED_CASE_EXECUTED " + name);
        }
    }
}
