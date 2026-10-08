package project.scheduler;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import project.model.FitnessFunction;
import project.model.ScheduleContext;
import project.model.ScheduleEvaluator;

/**
 * Clonal Selection Algorithm (CLONALG) over task-to-VM mappings.
 *
 * The population starts with the KPB k=100% mapping plus popSize-1 random
 * mappings. Each generation: sort by fitness; the antibody of rank r (1 = best,
 * up to nSelect) gets round(beta * popSize / r) clones, each hypermutated by
 * moving a fraction mutMin + (mutMax - mutMin) * (r - 1) / (nSelect - 1) of the
 * tasks to a different random VM; clones and population merge and the popSize
 * best survive; finally the d worst are replaced by new random mappings (receptor
 * editing). Stops after exactly maxEvaluations fitness evaluations, which may
 * fall in the middle of a generation.
 */
public final class ClonalSelectionScheduler implements MetaheuristicScheduler {
	public static final int DEFAULT_POP_SIZE = 30;
	public static final int DEFAULT_N_SELECT = 10;
	public static final double DEFAULT_BETA = 1.0;
	public static final double DEFAULT_MUT_MIN = 0.01;
	public static final double DEFAULT_MUT_MAX = 0.10;
	public static final int DEFAULT_D = 3;
	public static final int DEFAULT_MAX_EVALUATIONS = 10_000;

	private static final class Antibody {
		final int[] mapping;
		final double fitness;

		Antibody(int[] mapping, double fitness) {
			this.mapping = mapping;
			this.fitness = fitness;
		}
	}

	private static final Comparator<Antibody> BY_FITNESS = Comparator.comparingDouble(a -> a.fitness);

	private final ScheduleContext topology;
	private final double w;
	private final int popSize;
	private final int nSelect;
	private final double beta;
	private final double mutMin;
	private final double mutMax;
	private final int d;
	private final int maxEvaluations;
	private final long seed;
	private final int logEvery;

	private double bestFitness = Double.NaN;
	private long evaluations;
	private ConvergenceLog convergence;

	public ClonalSelectionScheduler(ScheduleContext topology, int maxEvaluations, long seed) {
		this(topology, FitnessFunction.DEFAULT_W, DEFAULT_POP_SIZE, DEFAULT_N_SELECT, DEFAULT_BETA,
				DEFAULT_MUT_MIN, DEFAULT_MUT_MAX, DEFAULT_D, maxEvaluations, seed, ConvergenceLog.DEFAULT_EVERY);
	}

	/** @param topology hosts and VM placement; tasks and capacities come from schedule() */
	public ClonalSelectionScheduler(ScheduleContext topology, double w, int popSize, int nSelect, double beta,
									double mutMin, double mutMax, int d, int maxEvaluations, long seed,
									int logEvery) {
		if (popSize < 1 || nSelect < 1 || nSelect > popSize) {
			throw new IllegalArgumentException("Need 1 <= nSelect <= popSize");
		}
		if (d < 0 || d >= popSize) {
			throw new IllegalArgumentException("Need 0 <= d < popSize so the best antibody survives");
		}
		if (!(beta > 0)) {
			throw new IllegalArgumentException("beta must be positive");
		}
		if (!(mutMin >= 0 && mutMin <= mutMax && mutMax <= 1)) {
			throw new IllegalArgumentException("Need 0 <= mutMin <= mutMax <= 1");
		}
		if (maxEvaluations < popSize) {
			throw new IllegalArgumentException("maxEvaluations must be at least popSize");
		}
		this.topology = topology;
		this.w = w;
		this.popSize = popSize;
		this.nSelect = nSelect;
		this.beta = beta;
		this.mutMin = mutMin;
		this.mutMax = mutMax;
		this.d = d;
		this.maxEvaluations = maxEvaluations;
		this.seed = seed;
		this.logEvery = logEvery;
	}

	@Override
	public String getName() {
		return String.format(Locale.US,
				"CSA(pop=%d,nSelect=%d,beta=%.2f,mut=%.2f-%.2f,d=%d,evals=%d,w=%.2f,seed=%d)",
				popSize, nSelect, beta, mutMin, mutMax, d, maxEvaluations, w, seed);
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
		int[] order = new int[tasks];
		for (int task = 0; task < tasks; task++) {
			order[task] = task;
		}

		List<Antibody> population = new ArrayList<>(popSize);
		Antibody best = null;
		for (int i = 0; i < popSize; i++) {
			int[] mapping = i == 0 ? kpbMapping : HarmonySearchScheduler.randomMapping(tasks, vms, random);
			Antibody antibody = new Antibody(mapping, fitness.evaluate(mapping));
			population.add(antibody);
			best = better(best, antibody);
			log.record(fitness.evaluations(), best.fitness);
		}

		while (fitness.evaluations() < maxEvaluations) {
			population.sort(BY_FITNESS);
			List<Antibody> clones = new ArrayList<>();
			cloning:
			for (int rank = 1; rank <= nSelect; rank++) {
				Antibody parent = population.get(rank - 1);
				int cloneCount = (int) Math.round(beta * popSize / rank);
				int moves = mutatedTaskCount(rank, tasks);
				for (int c = 0; c < cloneCount; c++) {
					if (fitness.evaluations() >= maxEvaluations) {
						break cloning;
					}
					int[] child = parent.mapping.clone();
					hypermutate(child, moves, vms, order, random);
					Antibody clone = new Antibody(child, fitness.evaluate(child));
					clones.add(clone);
					best = better(best, clone);
					log.record(fitness.evaluations(), best.fitness);
				}
			}

			population.addAll(clones);
			population.sort(BY_FITNESS);
			population = new ArrayList<>(population.subList(0, popSize));

			for (int i = 0; i < d && fitness.evaluations() < maxEvaluations; i++) {
				int[] mapping = HarmonySearchScheduler.randomMapping(tasks, vms, random);
				Antibody fresh = new Antibody(mapping, fitness.evaluate(mapping));
				population.set(popSize - 1 - i, fresh);
				best = better(best, fresh);
				log.record(fitness.evaluations(), best.fitness);
			}
		}
		log.finish(fitness.evaluations(), best.fitness);

		bestFitness = best.fitness;
		evaluations = fitness.evaluations();
		convergence = log;
		return best.mapping.clone();
	}

	/** Tasks to move for a clone of the given rank: better antibodies mutate less. */
	int mutatedTaskCount(int rank, int tasks) {
		double fraction = nSelect == 1 ? mutMin : mutMin + (mutMax - mutMin) * (rank - 1) / (nSelect - 1);
		return Math.min(tasks, Math.max(1, (int) Math.round(fraction * tasks)));
	}

	/** Moves {@code moves} distinct random tasks to a different random VM each. */
	private static void hypermutate(int[] mapping, int moves, int vms, int[] order, Random random) {
		if (vms < 2) {
			return;
		}
		// Partial Fisher-Yates over a persistent permutation picks distinct tasks in O(moves).
		for (int k = 0; k < moves; k++) {
			int j = k + random.nextInt(order.length - k);
			int swap = order[k];
			order[k] = order[j];
			order[j] = swap;
			int task = order[k];
			mapping[task] = (mapping[task] + 1 + random.nextInt(vms - 1)) % vms;
		}
	}

	private static Antibody better(Antibody current, Antibody candidate) {
		return current == null || candidate.fitness < current.fitness ? candidate : current;
	}

	@Override public long seed() { return seed; }
	@Override public int maxEvaluations() { return maxEvaluations; }
	@Override public double bestFitness() { return bestFitness; }
	@Override public long evaluations() { return evaluations; }
	@Override public ConvergenceLog convergence() { return convergence; }
}
