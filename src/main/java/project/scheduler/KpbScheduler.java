package project.scheduler;

import java.util.Arrays;

/**
 * Capacity-aware list scheduling. Tasks are considered longest first and are
 * assigned to the VM with the smallest projected completion time.
 */
public final class KpbScheduler implements Scheduler {

	@Override
	public String getName() {
		return "KPB";
	}

	@Override
	public int[] schedule(double[] taskLength, double[] vmCapacity) {
		validate(taskLength, vmCapacity);

		Integer[] taskOrder = new Integer[taskLength.length];
		for (int i = 0; i < taskLength.length; i++) {
			taskOrder[i] = i;
		}
		Arrays.sort(taskOrder, (left, right) -> {
			int byLength = Double.compare(taskLength[right], taskLength[left]);
			return byLength != 0 ? byLength : Integer.compare(left, right);
		});

		double[] load = new double[vmCapacity.length];
		int[] mapping = new int[taskLength.length];
		for (int task : taskOrder) {
			int selectedVm = 0;
			double bestFinish = Double.POSITIVE_INFINITY;
			for (int vm = 0; vm < vmCapacity.length; vm++) {
				double finish = load[vm] + taskLength[task] / vmCapacity[vm];
				if (finish < bestFinish
						|| (Double.compare(finish, bestFinish) == 0
						&& vmCapacity[vm] > vmCapacity[selectedVm])) {
					bestFinish = finish;
					selectedVm = vm;
				}
			}
			mapping[task] = selectedVm;
			load[selectedVm] += taskLength[task] / vmCapacity[selectedVm];
		}
		return mapping;
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
