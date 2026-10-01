package project.metrics;

import project.model.FitnessEvaluator;

public final class MetricsCollector {
	private MetricsCollector() { }

	public static Result collect(String scheduler, double[] taskLengths,
								 double[] vmCapacities, int[] mapping) {
		FitnessEvaluator evaluator = new FitnessEvaluator(taskLengths, vmCapacities);
		double makespan = evaluator.makespan(mapping);
		// Tasks arrive at t = 0 and run in submission (index) order on each VM,
		// so response time = finish time = queue wait + execution time.
		double[] readyTime = new double[vmCapacities.length];
		double totalResponse = 0;
		for (int task = 0; task < mapping.length; task++) {
			readyTime[mapping[task]] += taskLengths[task] / vmCapacities[mapping[task]];
			totalResponse += readyTime[mapping[task]];
		}
		return new Result(scheduler, makespan,
				mapping.length == 0 ? 0 : totalResponse / mapping.length,
				makespan == 0 ? 0 : mapping.length / makespan,
				evaluator.vmLoads(mapping));
	}
}
