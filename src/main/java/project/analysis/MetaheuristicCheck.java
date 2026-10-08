package project.analysis;

import ch.qos.logback.classic.Level;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;
import org.cloudbus.cloudsim.schedulers.cloudlet.CloudletScheduler;
import org.cloudsimplus.util.Log;
import org.slf4j.LoggerFactory;
import project.data.GoCJLoader;
import project.metrics.MetricsCollector;
import project.metrics.Result;
import project.model.ScheduleContext;
import project.model.ScheduleEvaluator;
import project.scheduler.HarmonySearchScheduler;
import project.scheduler.KpbScheduler;
import project.scheduler.MetaheuristicScheduler;
import project.setup.CloudSimAdapter;
import project.setup.SimulationSetup;
import project.setup.VmFactory;

/**
 * Runs a metaheuristic against KPB k=100% on each dataset and seed, validates the
 * resulting mapping in CloudSim, and writes one convergence CSV per run to
 * results/analysis/.
 *
 * Usage: MetaheuristicCheck algo [dataset1,dataset2,...] [seed1,seed2,...] [maxEvaluations]
 *        algo = hs
 */
public final class MetaheuristicCheck {
	private static final Path OUTPUT_DIR = Path.of("results", "analysis");

	private MetaheuristicCheck() { }

	public static void main(String[] args) throws IOException {
		Log.setLevel(Level.toLevel(System.getProperty("log", "error"), Level.ERROR));
		Log.setLevel(LoggerFactory.getLogger(CloudletScheduler.class.getSimpleName()), Level.ERROR);
		String algo = args.length > 0 ? args[0] : "hs";
		String[] datasets = args.length > 1 ? args[1].split(",")
				: new String[] {"src/main/resources/dataset/GoCJ_Dataset_100.txt",
						"src/main/resources/dataset/GoCJ_Dataset_500.txt",
						"src/main/resources/dataset/GoCJ_Dataset_1000.txt"};
		long[] seeds = args.length > 2
				? Arrays.stream(args[2].split(",")).mapToLong(Long::parseLong).toArray()
				: new long[] {1, 2, 3};
		int maxEvaluations = args.length > 3 ? Integer.parseInt(args[3])
				: HarmonySearchScheduler.DEFAULT_MAX_EVALUATIONS;

		ScheduleContext topology = ScheduleContext.fromDesign(new double[0]);
		double[] capacities = CloudSimAdapter.vmCapacities(VmFactory.createDefaultVms());

		System.out.printf(Locale.US, "%-18s %4s | %9s %9s | %8s %9s %7s %9s %7s %8s | %9s %9s%n",
				"dataset", "seed", "kpb_ms", "kpb_kWh", "fitness", "ms_eval", "dMs%", "kWh_eval", "dE%",
				"sched_ms", "ms_cs", "kWh_cs");
		for (String datasetArg : datasets) {
			Path path = Path.of(datasetArg.trim());
			String name = path.getFileName().toString().replaceFirst("\\.txt$", "");
			double[] tasks = GoCJLoader.load(path);
			ScheduleEvaluator evaluator = new ScheduleEvaluator(topology.withTasks(tasks, capacities));
			int[] kpb = new KpbScheduler(100).schedule(tasks, capacities);
			double kpbMakespan = evaluator.makespan(kpb);
			double kpbEnergy = evaluator.energyKwh(kpb);

			for (long seed : seeds) {
				MetaheuristicScheduler scheduler = create(algo, topology, maxEvaluations, seed);
				long start = System.nanoTime();
				int[] mapping = scheduler.schedule(tasks, capacities);
				double schedulingMillis = (System.nanoTime() - start) / 1e6;

				double makespan = evaluator.makespan(mapping);
				double energy = evaluator.energyKwh(mapping);

				SimulationSetup setup = new SimulationSetup(tasks);
				setup.run(mapping);
				Result cloudSim = MetricsCollector.collect(scheduler.getName(), setup, schedulingMillis);

				System.out.printf(Locale.US,
						"%-18s %4d | %9.2f %9.5f | %8.5f %9.2f %6.2f%% %9.5f %6.2f%% %8.1f | %9.2f %9.5f%n",
						name, seed, kpbMakespan, kpbEnergy, scheduler.bestFitness(),
						makespan, 100 * (makespan - kpbMakespan) / kpbMakespan,
						energy, 100 * (energy - kpbEnergy) / kpbEnergy, schedulingMillis,
						cloudSim.makespan(), cloudSim.energyKwh());

				scheduler.convergence().writeCsv(OUTPUT_DIR.resolve(
						String.format(Locale.US, "convergence_%s_%s_seed%d.csv", algo, name, seed)));
			}
		}
	}

	private static MetaheuristicScheduler create(String algo, ScheduleContext topology, int maxEvaluations,
												 long seed) {
		switch (algo) {
			case "hs":
				return new HarmonySearchScheduler(topology, maxEvaluations, seed);
			default:
				throw new IllegalArgumentException("Unknown algorithm: " + algo);
		}
	}
}
