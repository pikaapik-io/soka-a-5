package project;

import java.nio.file.Path;
import project.data.GoCJLoader;
import project.metrics.MetricsCollector;
import project.metrics.Result;
import project.scheduler.KpbScheduler;
import project.scheduler.Scheduler;

public final class Experiment {
    private Experiment() { }

    public static void main(String[] args) throws Exception {
	double[] taskLengths = args.length == 0
		? new double[] {900_000, 600_000, 450_000, 300_000, 150_000}
		: GoCJLoader.load(Path.of(args[0]));
	double[] vmCapacities = {
		10_000, 10_000, 10_000, 10_000,
		3_000, 3_000, 3_000, 3_000, 3_000, 3_000, 3_000, 3_000,
		1_000, 1_000, 1_000, 1_000, 1_000, 1_000, 1_000, 1_000
	};

	Scheduler scheduler = new KpbScheduler();
	int[] mapping = scheduler.schedule(taskLengths, vmCapacities);
	Result result = MetricsCollector.collect(scheduler.getName(), taskLengths,
		vmCapacities, mapping);

	System.out.printf("scheduler=%s%n", result.scheduler());
	System.out.printf("tasks=%d, makespan=%.3f, avgResponse=%.3f, throughput=%.3f%n",
		taskLengths.length, result.makespan(), result.averageResponse(), result.throughput());
	System.out.println("mapping=" + java.util.Arrays.toString(mapping));
	System.out.println("vmLoads=" + java.util.Arrays.toString(result.vmLoads()));
    }
}
