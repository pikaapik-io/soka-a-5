package project.model;

import java.util.Arrays;

/**
 * Shared fitness evaluator for every scheduler (KPB, HS, CSA): makespan from the
 * sequential-queue model and energy from CloudSim's linear power model (design
 * section 3.2), both in O(task count) per evaluation.
 *
 * Makespan: load_j = sum over tasks mapped to VM j of L_i / C_j, makespan = max_j load_j.
 * Energy:   idle = sum_h P_idle_h * makespan (every host stays on for the whole run);
 *           dynamic = sum_j (P_max_h - P_idle_h) * (C_j / MIPS_h) * load_j, h = host of VM j.
 * This matches EnergyMeter: while VM j is busy it saturates its own capacity, so the
 * host's utilization integral contributed by VM j is (C_j / MIPS_h) * load_j.
 */
public final class ScheduleEvaluator {
	private final double[] taskLength;
	private final double[] vmCapacity;
	private final int[] vmHost;
	private final double[] hostIdlePower;
	private final double[] hostMaxPower;
	private final double[] hostTotalMips;
	private final int vmCount;
	private final int hostCount;

	public ScheduleEvaluator(ScheduleContext context) {
		this.taskLength = context.taskLength();
		this.vmCapacity = context.vmCapacity();
		this.vmHost = context.vmHost();
		this.hostIdlePower = context.hostIdlePower();
		this.hostMaxPower = context.hostMaxPower();
		this.hostTotalMips = context.hostTotalMips();
		this.vmCount = vmCapacity.length;
		this.hostCount = hostIdlePower.length;
	}

	/** load_j = sum of L_i / C_j over tasks assigned to VM j. O(task count). */
	public double[] vmLoads(int[] mapping) {
		validateMapping(mapping);
		double[] loads = new double[vmCount];
		for (int task = 0; task < mapping.length; task++) {
			int vm = mapping[task];
			loads[vm] += taskLength[task] / vmCapacity[vm];
		}
		return loads;
	}

	public double makespan(int[] mapping) {
		return makespanFromLoads(vmLoads(mapping));
	}

	public double makespanFromLoads(double[] loads) {
		return Arrays.stream(loads).max().orElse(0);
	}

	public EnergyBreakdown energyBreakdown(int[] mapping) {
		return energyBreakdownFromLoads(vmLoads(mapping));
	}

	/** Idle + dynamic energy in Joules for the given VM loads. O(VM count + host count). */
	public EnergyBreakdown energyBreakdownFromLoads(double[] loads) {
		double makespan = makespanFromLoads(loads);
		double idle = 0;
		for (int h = 0; h < hostCount; h++) {
			idle += hostIdlePower[h] * makespan;
		}
		double dynamic = 0;
		for (int vm = 0; vm < vmCount; vm++) {
			int h = vmHost[vm];
			dynamic += (hostMaxPower[h] - hostIdlePower[h]) * (vmCapacity[vm] / hostTotalMips[h]) * loads[vm];
		}
		return new EnergyBreakdown(idle, dynamic);
	}

	public double energyJoules(int[] mapping) {
		return energyBreakdown(mapping).totalJoules();
	}

	public double energyKwh(int[] mapping) {
		return energyJoules(mapping) / 3_600_000;
	}

	/** F = w * (makespan / makespanRef) + (1 - w) * (energyKwh / energyRef), to minimize. */
	public double fitness(int[] mapping, double w, double makespanRef, double energyRef) {
		if (!Double.isFinite(w) || w < 0 || w > 1) {
			throw new IllegalArgumentException("w must be in [0, 1]");
		}
		double[] loads = vmLoads(mapping);
		double energyKwh = energyBreakdownFromLoads(loads).totalKwh();
		return w * (makespanFromLoads(loads) / makespanRef) + (1 - w) * (energyKwh / energyRef);
	}

	// ── Incremental evaluation: moving one task between VMs, O(1) ────────────

	/** Loads after moving a task of the given length from one VM to another. */
	public double[] moveTask(double[] loads, double movedTaskLength, int fromVm, int toVm) {
		double[] updated = loads.clone();
		updated[fromVm] -= movedTaskLength / vmCapacity[fromVm];
		updated[toVm] += movedTaskLength / vmCapacity[toVm];
		return updated;
	}

	public int vmCount() { return vmCount; }
	public int hostCount() { return hostCount; }
	public int taskCount() { return taskLength.length; }

	private void validateMapping(int[] mapping) {
		if (mapping == null || mapping.length != taskLength.length) {
			throw new IllegalArgumentException("Mapping must contain one VM for every task");
		}
		for (int vm : mapping) {
			if (vm < 0 || vm >= vmCount) {
				throw new IllegalArgumentException("Mapping contains an invalid VM index");
			}
		}
	}

	/** Energy split into idle (always on) and dynamic (load-dependent) components. */
	public static final class EnergyBreakdown {
		private final double idleJoules;
		private final double dynamicJoules;

		public EnergyBreakdown(double idleJoules, double dynamicJoules) {
			this.idleJoules = idleJoules;
			this.dynamicJoules = dynamicJoules;
		}

		public double idleJoules() { return idleJoules; }
		public double dynamicJoules() { return dynamicJoules; }
		public double totalJoules() { return idleJoules + dynamicJoules; }
		public double totalKwh() { return totalJoules() / 3_600_000; }
		public double dynamicShare() { return totalJoules() == 0 ? 0 : dynamicJoules / totalJoules(); }
	}
}
