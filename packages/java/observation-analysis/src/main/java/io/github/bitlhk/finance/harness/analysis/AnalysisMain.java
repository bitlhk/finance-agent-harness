package io.github.bitlhk.finance.harness.analysis;

/** Fixed single-snapshot stdin/stdout entry; this is not an OS sandbox. */
public final class AnalysisMain {
    private AnalysisMain() { }
    public static void main(String[] args) {
        try {
            if (args.length != 0) throw new IllegalArgumentException();
            byte[] input = System.in.readNBytes(ObservationAnalyzer.MAX_INPUT + 1);
            byte[] output = ObservationAnalyzer.encode(ObservationAnalyzer.analyze(ObservationAnalyzer.parse(input)));
            if (output.length > ObservationAnalyzer.MAX_OUTPUT) throw new IllegalArgumentException();
            System.out.write(output); System.out.write('\n'); System.out.flush();
        } catch (Exception error) {
            // Never disclose parser text, exception bodies, source fields or paths.
            System.out.println("{\"schemaVersion\":\"finance.observation-analysis-error.v1\",\"code\":\"ANALYSIS_INPUT_INVALID\"}");
            System.exit(2);
        }
    }
}
