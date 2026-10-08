package project.scheduler;

/** A budgeted, seeded search scheduler; the getters describe its most recent schedule() call. */
public interface MetaheuristicScheduler extends Scheduler {

	long seed();

	int maxEvaluations();

	double bestFitness();

	/** Fitness evaluations performed, always equal to maxEvaluations after schedule(). */
	long evaluations();

	ConvergenceLog convergence();
}
