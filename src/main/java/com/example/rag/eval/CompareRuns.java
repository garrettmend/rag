package com.example.rag.eval;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Command-line tool that compares two evaluation runs side by side.
 * It reads a baseline and a candidate CSV from the eval-results directory and summarizes each one.
 * Hit rate, average judge score, and average latency are printed with the candidate's delta from the baseline.
 */
public class CompareRuns {

    /**
     * Prints a comparison table for two result files.
     *
     * @param args the baseline and candidate CSV file names, relative to eval-results
     * @throws Exception if either file cannot be read or parsed
     */
    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.out.println("Usage: CompareRuns <baseline.csv> <candidate.csv>");
            return;
        }
        Summary baseline = summarize(Path.of("eval-results", args[0]));
        Summary candidate = summarize(Path.of("eval-results", args[1]));

        System.out.printf("%-18s %10s %10s %10s%n", "metric", "baseline", "candidate", "delta");
        System.out.printf("%-18s %9.1f%% %9.1f%% %+9.1f%%%n", "hit rate",
                baseline.hitRate() * 100, candidate.hitRate() * 100,
                (candidate.hitRate() - baseline.hitRate()) * 100);
        System.out.printf("%-18s %10.2f %10.2f %+10.2f%n", "avg judge score",
                baseline.avgJudgeScore(), candidate.avgJudgeScore(),
                candidate.avgJudgeScore() - baseline.avgJudgeScore());
        System.out.printf("%-18s %8.0fms %8.0fms %+8.0fms%n", "avg latency",
                baseline.avgLatencyMs(), candidate.avgLatencyMs(),
                candidate.avgLatencyMs() - baseline.avgLatencyMs());
    }

    /**
     * Aggregate metrics for one evaluation run.
     *
     * @param hitRate       fraction of cases whose retrieval contained the expected chunk
     * @param avgJudgeScore mean LLM judge score
     * @param avgLatencyMs  mean end-to-end query latency in milliseconds
     */
    private record Summary(double hitRate, double avgJudgeScore, double avgLatencyMs) {}

    /**
     * Reads a results CSV (with a header row) and averages its metrics.
     *
     * @param csvPath the results file to summarize
     * @return the run's aggregate metrics
     * @throws Exception if the file cannot be read or a row is malformed
     */
    private static Summary summarize(Path csvPath) throws Exception {
        List<String> lines = Files.readAllLines(csvPath);
        int hits = 0, totalJudge = 0;
        long totalLatency = 0;
        int count = lines.size() - 1;
        for (int i = 1; i < lines.size(); i++) {
            String[] cols = lines.get(i).split(",");
            if (Boolean.parseBoolean(cols[1])) hits++;
            totalJudge += Integer.parseInt(cols[2]);
            totalLatency += Long.parseLong(cols[3]);
        }
        return new Summary((double) hits / count, (double) totalJudge / count, (double) totalLatency / count);
    }
}
