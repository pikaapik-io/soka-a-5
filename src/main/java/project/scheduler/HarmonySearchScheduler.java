package project.scheduler;

import java.util.Locale;
import java.util.Random;
import project.model.FitnessFunction;
import project.model.ScheduleContext;
import project.model.ScheduleEvaluator;

/**
 * Harmony Search over task-to-VM mappings (int[] mapping, one VM index per task).
 *
 * Harmony memory starts with the KPB k=100% mapping plus HMS-1 random mappings.
 * Each new harmony is built task by task: with probability HMCR the task's VM is
 * copied from a random harmony in memory (then, with probability PAR, moved to a
 * different random VM); otherwise a random VM is chosen. A new harmony replaces
 * the worst one in memory if it is strictly better. Stops after exactly
 * maxEvaluations fitness evaluations (the initial memory included).
 */
public final class HarmonySearchScheduler implements MetaheuristicScheduler {
	public static final int DEFAULT_HMS = 30;
	public static final double DEFAULT_HMCR = 0.9;
	public static final double DEFAULT_PAR = 0.3;
	public static final int DEFAULT_MAX_EVALUATIONS = 10_000;

	private final ScheduleContext topology;
	private final double w;
	private final int hms;
	private final double hmcr;
	private final double par;
	private final int maxEvaluations;
	private final long seed;
	private final int logEvery;

	private double bestFitness = Double.NaN;
	private long evaluations;
	private ConvergenceLog convergence;

	public HarmonySearchScheduler(ScheduleContext topology, int maxEvaluations, long seed) {
		this(topology, FitnessFunction.DEFAULT_W, DEFAULT_HMS, DEFAULT_HMCR, DEFAULT_PAR,
				maxEvaluations, seed, ConvergenceLog.DEFAULT_EVERY);
	}

	/** @param topology hosts and VM placement; tasks and capacities come from schedule() */
	public HarmonySearchScheduler(ScheduleContext topology, double w, int hms, double hmcr, double par,
								  int maxEvaluations, long seed, int logEvery) {
		if (hms < 1) {
			throw new IllegalArgumentException("HMS must be at least 1");
		}
		if (!(hmcr >= 0 && hmcr <= 1) || !(par >= 0 && par <= 1)) {
			throw new IllegalArgumentException("HMCR and PAR must be in [0, 1]");
		}
		if (maxEvaluations < hms) {
			throw new IllegalArgumentException("maxEvaluations must be at least HMS");
		}
		this.topology = topology;
		this.w = w;
		this.hms = hms;
		this.hmcr = hmcr;
		this.par = par;
		this.maxEvaluations = maxEvaluations;
		this.seed = seed;
		this.logEvery = logEvery;
	}

	@Override
	public String getName() {
		return String.format(Locale.US, "HS(HMS=%d,HMCR=%.2f,PAR=%.2f,evals=%d,w=%.2f,seed=%d)",
				hms, hmcr, par, maxEvaluations, w, seed);
	}

	@Override
	public int[] schedule(double[] taskLength, double[] vmCapacity) {
		int[] kpbMapping = new KpbScheduler(100).schedule(taskLength, vmCapacity);
		FitnessFunction fitness = new FitnessFunction(
				new ScheduleEvaluator(topology.withTasks(taskLength, vmCapacity)), w, kpbMapping);
		Random random = new Random(seed);
		ConvergenceLog log = new ConvergenceLog(logEvery);
		int tasks = taskLength.length;
		int vms = vmCapacity.length;

		int[][] memory = new int[hms][];
		double[] score = new double[hms];
		int best = 0;
		for (int h = 0; h < hms; h++) {
			memory[h] = h == 0 ? kpbMapping : randomMapping(tasks, vms, random);
			score[h] = fitness.evaluate(memory[h]);
			if (score[h] < score[best]) {
				best = h;
			}
			log.record(fitness.evaluations(), score[best]);
		}

		while (fitness.evaluations() < maxEvaluations) {
			int[] harmony = new int[tasks];
			for (int task = 0; task < tasks; task++) {
				int vm;
				if (random.nextDouble() < hmcr) {
					vm = memory[random.nextInt(hms)][task];
					if (vms > 1 && random.nextDouble() < par) {
						vm = (vm + 1 + random.nextInt(vms - 1)) % vms;
					}
				} else {
					vm = random.nextInt(vms);
				}
				harmony[task] = vm;
			}
			double candidate = fitness.evaluate(harmony);

			int worst = 0;
			for (int h = 1; h < hms; h++) {
				if (score[h] > score[worst]) {
					worst = h;
				}
			}
			if (candidate < score[worst]) {
				memory[worst] = harmony;
				score[worst] = candidate;
				if (candidate < score[best]) {
					best = worst;
				}
			}
			log.record(fitness.evaluations(), score[best]);
		}
		log.finish(fitness.evaluations(), score[best]);

		bestFitness = score[best];
		evaluations = fitness.evaluations();
		convergence = log;
		return memory[best].clone();
	}

	static int[] randomMapping(int tasks, int vms, Random random) {
		int[] mapping = new int[tasks];
		for (int task = 0; task < tasks; task++) {
			mapping[task] = random.nextInt(vms);
		}
		return mapping;
	}

	@Override public long seed() { return seed; }
	@Override public int maxEvaluations() { return maxEvaluations; }
	@Override public double bestFitness() { return bestFitness; }
	@Override public long evaluations() { return evaluations; }
	@Override public ConvergenceLog convergence() { return convergence; }
}
