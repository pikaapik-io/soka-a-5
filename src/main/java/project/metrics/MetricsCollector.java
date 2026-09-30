package project.metrics;

import project.model.FitnessEvaluator;

public final class MetricsCollector {
	private MetricsCollector() { }

	public static Result collect(String scheduler, double[] taskLengths,
								 double[] vmCapacities, int[] mapping) {
		FitnessEvaluator evaluator = new FitnessEvaluator(taskLengths, vmCapacities);
		double makespan = evaluator.makespan(mapping);
		double totalResponse = 0;
		for (int task = 0; task < mapping.length; task++) {
			totalResponse += taskLengths[task] / vmCapacities[mapping[task]];
		}
		return new Result(scheduler, makespan,
				mapping.length == 0 ? 0 : totalResponse / mapping.length,
				makespan == 0 ? 0 : mapping.length / makespan,
				evaluator.vmLoads(mapping));
	}
}
