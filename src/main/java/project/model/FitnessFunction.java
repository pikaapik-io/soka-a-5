package project.model;

/**
 * F = w * (makespan / makespanRef) + (1 - w) * (energy / energyRef), to minimize.
 * The references come from a reference mapping (KPB k=100% on the same dataset),
 * so that mapping scores exactly 1.0. Every call to {@link #evaluate} is counted,
 * which is how metaheuristics enforce their evaluation budget.
 */
public final class FitnessFunction {
	public static final double DEFAULT_W = 0.5;

	private final ScheduleEvaluator evaluator;
	private final double w;
	private final double makespanRef;
	private final double energyRef;
	private long evaluations;

	public FitnessFunction(ScheduleEvaluator evaluator, double w, int[] referenceMapping) {
		if (!Double.isFinite(w) || w < 0 || w > 1) {
			throw new IllegalArgumentException("w must be in [0, 1]");
		}
		this.evaluator = evaluator;
		this.w = w;
		this.makespanRef = evaluator.makespan(referenceMapping);
		this.energyRef = evaluator.energyKwh(referenceMapping);
		if (makespanRef <= 0 || energyRef <= 0) {
			throw new IllegalArgumentException("Reference mapping must have positive makespan and energy");
		}
	}

	public double evaluate(int[] mapping) {
		evaluations++;
		return evaluator.fitness(mapping, w, makespanRef, energyRef);
	}

	public long evaluations() { return evaluations; }
	public double w() { return w; }
	public double makespanRef() { return makespanRef; }
	public double energyRef() { return energyRef; }
	public ScheduleEvaluator evaluator() { return evaluator; }
}
