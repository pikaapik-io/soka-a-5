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
import project.scheduler.KpbScheduler;
import project.scheduler.Scheduler;
import project.setup.CloudSimAdapter;
import project.setup.SimulationSetup;
import project.setup.VmFactory;

/**
 * Fase 3 verification: for KPB mappings, compares ScheduleEvaluator's analytic
 * makespan/energy against a live CloudSim run. Requirement: energy difference
 * under 3%.
 *
 * Usage: EvaluatorCheck [dataset1,dataset2,...] [k1,k2,...]
 */
public final class EvaluatorCheck {
	private EvaluatorCheck() { }

	public static void main(String[] args) throws IOException {
		Log.setLevel(Level.toLevel(System.getProperty("log", "error"), Level.ERROR));
		Log.setLevel(LoggerFactory.getLogger(CloudletScheduler.class.getSimpleName()), Level.ERROR);
		String[] datasets = args.length > 0 ? args[0].split(",")
				: new String[] {"src/main/resources/dataset/GoCJ_Dataset_100.txt",
						"src/main/resources/dataset/GoCJ_Dataset_500.txt",
						"src/main/resources/dataset/GoCJ_Dataset_1000.txt"};
		double[] kPercents = args.length > 1
				? Arrays.stream(args[1].split(",")).mapToDouble(Double::parseDouble).toArray()
				: new double[] {20, 50, 100};

		System.out.printf(Locale.US, "%-24s %5s | %11s %11s %7s | %10s %10s %7s%n",
				"dataset", "k%", "makespan_cs", "makespan_ev", "diff%", "energy_cs", "energy_ev", "diff%");
		for (String datasetArg : datasets) {
			Path path = Path.of(datasetArg.trim());
			double[] taskLengths = GoCJLoader.load(path);
			for (double k : kPercents) {
				check(path, taskLengths, k);
			}
		}
	}

	private static void check(Path path, double[] taskLengths, double k) {
		Scheduler scheduler = new KpbScheduler(k);
		double[] vmCapacities = CloudSimAdapter.vmCapacities(VmFactory.createDefaultVms());
		int[] mapping = scheduler.schedule(taskLengths, vmCapacities);

		SimulationSetup setup = new SimulationSetup(taskLengths);
		setup.run(mapping);
		Result result = MetricsCollector.collect(scheduler.getName(), setup, 0);

		ScheduleEvaluator evaluator = new ScheduleEvaluator(ScheduleContext.fromDesign(taskLengths));
		double evalMakespan = evaluator.makespan(mapping);
		double evalEnergy = evaluator.energyKwh(mapping);

		double makespanDiff = 100 * Math.abs(evalMakespan - result.makespan()) / result.makespan();
		double energyDiff = 100 * Math.abs(evalEnergy - result.energyKwh()) / result.energyKwh();

		System.out.printf(Locale.US, "%-24s %5.0f | %11.4f %11.4f %6.2f%% | %10.6f %10.6f %6.2f%%%n",
				path.getFileName(), k, result.makespan(), evalMakespan, makespanDiff,
				result.energyKwh(), evalEnergy, energyDiff);
	}
}
