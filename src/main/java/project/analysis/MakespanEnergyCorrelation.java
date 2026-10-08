package project.analysis;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Locale;
import java.util.Random;
import project.data.GoCJLoader;
import project.model.ScheduleContext;
import project.model.ScheduleEvaluator;

/**
 * Fase 3 required analysis: 1000 random mappings on a dataset, to check whether
 * makespan and energy are genuinely different objectives (Pearson correlation)
 * and how large the dynamic energy component is relative to total energy.
 *
 * Usage: MakespanEnergyCorrelation [dataset] [sampleCount] [seed]
 */
public final class MakespanEnergyCorrelation {
	private static final Path OUTPUT_DIR = Path.of("results", "analysis");

	private MakespanEnergyCorrelation() { }

	public static void main(String[] args) throws IOException {
		Path dataset = Path.of(args.length > 0 ? args[0] : "src/main/resources/dataset/GoCJ_Dataset_500.txt");
		int samples = args.length > 1 ? Integer.parseInt(args[1]) : 1000;
		long seed = args.length > 2 ? Long.parseLong(args[2]) : 42L;

		double[] taskLengths = GoCJLoader.load(dataset);
		ScheduleContext context = ScheduleContext.fromDesign(taskLengths);
		ScheduleEvaluator evaluator = new ScheduleEvaluator(context);
		int vmCount = context.vmCount();

		Random random = new Random(seed);
		double[] makespans = new double[samples];
		double[] energies = new double[samples];
		double[] dynamicShares = new double[samples];

		Files.createDirectories(OUTPUT_DIR);
		String name = dataset.getFileName().toString().replaceFirst("\\.txt$", "");
		Path detail = OUTPUT_DIR.resolve("makespan_energy_" + name + ".csv");
		try (PrintWriter out = new PrintWriter(Files.newBufferedWriter(detail,
				StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING))) {
			out.println("sample,makespan_s,energy_kwh,dynamic_share");
			for (int s = 0; s < samples; s++) {
				int[] mapping = new int[taskLengths.length];
				for (int t = 0; t < mapping.length; t++) {
					mapping[t] = random.nextInt(vmCount);
				}
				ScheduleEvaluator.EnergyBreakdown breakdown = evaluator.energyBreakdown(mapping);
				makespans[s] = evaluator.makespanFromLoads(evaluator.vmLoads(mapping));
				energies[s] = breakdown.totalKwh();
				dynamicShares[s] = breakdown.dynamicShare();
				out.printf(Locale.US, "%d,%.6f,%.8f,%.6f%n", s, makespans[s], energies[s], dynamicShares[s]);
			}
		}

		double r = pearson(makespans, energies);
		double minShare = Arrays.stream(dynamicShares).min().orElse(0);
		double maxShare = Arrays.stream(dynamicShares).max().orElse(0);

		System.out.printf(Locale.US, "dataset=%s tasks=%d vms=%d samples=%d seed=%d%n",
				dataset.getFileName(), taskLengths.length, vmCount, samples, seed);
		System.out.printf(Locale.US, "korelasi Pearson(makespan, energy) = %.4f%n", r);
		System.out.printf(Locale.US,
				"komponen energi dinamis / total energi: min=%.4f (%.2f%%) max=%.4f (%.2f%%)%n",
				minShare, minShare * 100, maxShare, maxShare * 100);
		System.out.println("detail -> " + detail);
	}

	/** Pearson correlation coefficient of two equal-length samples. */
	static double pearson(double[] x, double[] y) {
		int n = x.length;
		double meanX = Arrays.stream(x).average().orElse(0);
		double meanY = Arrays.stream(y).average().orElse(0);
		double cov = 0;
		double varX = 0;
		double varY = 0;
		for (int i = 0; i < n; i++) {
			double dx = x[i] - meanX;
			double dy = y[i] - meanY;
			cov += dx * dy;
			varX += dx * dx;
			varY += dy * dy;
		}
		return cov / Math.sqrt(varX * varY);
	}
}
