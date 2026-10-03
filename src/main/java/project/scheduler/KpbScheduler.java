package project.scheduler;

import java.util.Arrays;

public final class KpbScheduler implements Scheduler {

	public static final double DEFAULT_K_PERCENT = 20.0;

	private final double kPercent;

	public KpbScheduler() {
		this(DEFAULT_K_PERCENT);
	}

	public KpbScheduler(double kPercent) {
		if (!Double.isFinite(kPercent) || kPercent <= 0 || kPercent > 100) {
			throw new IllegalArgumentException("k must be in the range (0, 100]");
		}
		this.kPercent = kPercent;
	}

	@Override
	public String getName() {
		return "KPB(k=" + kPercent + "%)";
	}

	public double kPercent() {
		return kPercent;
	}

	/** Number of candidate VMs: ceil(k% x m), at least one. */
	public int subsetSize(int vmCount) {
		return Math.max(1, Math.min(vmCount, (int) Math.ceil(kPercent / 100.0 * vmCount)));
	}

	@Override
	public int[] schedule(double[] taskLength, double[] vmCapacity) {
		validate(taskLength, vmCapacity);

		int subsetSize = subsetSize(vmCapacity.length);
		double[] readyTime = new double[vmCapacity.length];
		int[] mapping = new int[taskLength.length];

		// Step 1: tasks are taken in arrival order (index order).
		for (int task = 0; task < taskLength.length; task++) {
			// Step 2: rank VMs by execution time for this task, keep the best k%.
			Integer[] candidates = rankByExecutionTime(taskLength[task], vmCapacity);

			// Step 3: among the candidates, choose the minimum completion time.
			int selectedVm = candidates[0];
			double bestFinish = Double.POSITIVE_INFINITY;
			for (int c = 0; c < subsetSize; c++) {
				int vm = candidates[c];
				double finish = readyTime[vm] + taskLength[task] / vmCapacity[vm];
				if (finish < bestFinish) {
					bestFinish = finish;
					selectedVm = vm;
				}
			}

			// Step 4: commit the assignment and update the VM ready time.
			mapping[task] = selectedVm;
			readyTime[selectedVm] = bestFinish;
		}
		return mapping;
	}

	private static Integer[] rankByExecutionTime(double length, double[] vmCapacity) {
		Integer[] order = new Integer[vmCapacity.length];
		for (int vm = 0; vm < vmCapacity.length; vm++) {
			order[vm] = vm;
		}
		Arrays.sort(order, (left, right) -> {
			int byExec = Double.compare(length / vmCapacity[left], length / vmCapacity[right]);
			return byExec != 0 ? byExec : Integer.compare(left, right);
		});
		return order;
	}

	private static void validate(double[] taskLength, double[] vmCapacity) {
		if (taskLength == null || vmCapacity == null || vmCapacity.length == 0) {
			throw new IllegalArgumentException("Tasks and at least one VM are required");
		}
		for (double length : taskLength) {
			if (!Double.isFinite(length) || length < 0) {
				throw new IllegalArgumentException("Task lengths must be finite and non-negative");
			}
		}
		for (double capacity : vmCapacity) {
			if (!Double.isFinite(capacity) || capacity <= 0) {
				throw new IllegalArgumentException("VM capacities must be finite and positive");
			}
		}
	}
}
