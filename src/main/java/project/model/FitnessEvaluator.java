package project.model;

import java.util.Arrays;

public final class FitnessEvaluator {
	private final EtcMatrix etc;

	public FitnessEvaluator(double[] taskLength, double[] vmCapacity) {
		this.etc = new EtcMatrix(taskLength, vmCapacity);
	}

	public double makespan(int[] mapping) {
		validateMapping(mapping);
		double[] loads = new double[etc.vmCount()];
		for (int task = 0; task < mapping.length; task++) {
			loads[mapping[task]] += etc.get(task, mapping[task]);
		}
		return Arrays.stream(loads).max().orElse(0);
	}

	public double[] vmLoads(int[] mapping) {
		validateMapping(mapping);
		double[] loads = new double[etc.vmCount()];
		for (int task = 0; task < mapping.length; task++) {
			loads[mapping[task]] += etc.get(task, mapping[task]);
		}
		return loads;
	}

	private void validateMapping(int[] mapping) {
		if (mapping == null || mapping.length != etc.taskCount()) {
			throw new IllegalArgumentException("Mapping must contain one VM for every task");
		}
		for (int vm : mapping) {
			if (vm < 0 || vm >= etc.vmCount()) {
				throw new IllegalArgumentException("Mapping contains an invalid VM index");
			}
		}
	}
}
