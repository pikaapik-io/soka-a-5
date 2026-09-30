package project.model;

/** Expected completion-time matrix: ETC[task][vm] = task length / VM capacity. */
public final class EtcMatrix {
	private final double[][] values;

	public EtcMatrix(double[] taskLength, double[] vmCapacity) {
		if (taskLength == null || vmCapacity == null || vmCapacity.length == 0) {
			throw new IllegalArgumentException("Task lengths and VM capacities are required");
		}
		values = new double[taskLength.length][vmCapacity.length];
		for (int task = 0; task < taskLength.length; task++) {
			for (int vm = 0; vm < vmCapacity.length; vm++) {
				if (vmCapacity[vm] <= 0) {
					throw new IllegalArgumentException("VM capacity must be positive");
				}
				values[task][vm] = taskLength[task] / vmCapacity[vm];
			}
		}
	}

	public double get(int task, int vm) { return values[task][vm]; }
	public int taskCount() { return values.length; }
	public int vmCount() { return values.length == 0 ? 0 : values[0].length; }
}
