package project.scheduler;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import project.data.GoCJLoader;
import project.model.FitnessFunction;
import project.model.ScheduleContext;
import project.model.ScheduleEvaluator;
import project.setup.CloudSimAdapter;
import project.setup.VmFactory;

class HarmonySearchSchedulerTest {
	private static ScheduleContext topology;
	private static double[] tasks;
	private static double[] capacities;

	@BeforeAll
	static void setUp() throws IOException {
		topology = ScheduleContext.fromDesign(new double[0]);
		tasks = GoCJLoader.load(Path.of("src/main/resources/dataset/GoCJ_Dataset_100.txt"));
		capacities = CloudSimAdapter.vmCapacities(VmFactory.createDefaultVms());
	}

	@Test
	void sameSeedGivesIdenticalResult() {
		HarmonySearchScheduler a = new HarmonySearchScheduler(topology, 2_000, 7);
		HarmonySearchScheduler b = new HarmonySearchScheduler(topology, 2_000, 7);
		assertArrayEquals(a.schedule(tasks, capacities), b.schedule(tasks, capacities));
		assertEquals(a.bestFitness(), b.bestFitness());
	}

	@Test
	void everyVmIndexIsValid() {
		int[] mapping = new HarmonySearchScheduler(topology, 2_000, 3).schedule(tasks, capacities);
		assertEquals(tasks.length, mapping.length);
		for (int vm : mapping) {
			assertTrue(vm >= 0 && vm < capacities.length, "invalid VM index " + vm);
		}
	}

	@Test
	void resultIsNeverWorseThanKpbSeed() {
		HarmonySearchScheduler hs = new HarmonySearchScheduler(topology, 2_000, 11);
		int[] mapping = hs.schedule(tasks, capacities);

		int[] kpb = new KpbScheduler(100).schedule(tasks, capacities);
		FitnessFunction fitness = new FitnessFunction(
				new ScheduleEvaluator(topology.withTasks(tasks, capacities)), FitnessFunction.DEFAULT_W, kpb);
		double kpbFitness = fitness.evaluate(kpb);
		double hsFitness = fitness.evaluate(mapping);

		assertEquals(1.0, kpbFitness, 1e-12);
		assertEquals(hs.bestFitness(), hsFitness, 1e-12);
		assertTrue(hsFitness <= kpbFitness, "HS " + hsFitness + " worse than KPB " + kpbFitness);
	}

	@ParameterizedTest
	@ValueSource(ints = {30, 31, 101, 1_234})
	void evaluationBudgetIsRespectedExactly(int budget) {
		HarmonySearchScheduler hs = new HarmonySearchScheduler(topology, budget, 5);
		hs.schedule(tasks, capacities);
		assertEquals(budget, hs.evaluations());

		List<ConvergenceLog.Point> points = hs.convergence().points();
		assertEquals(budget, points.get(points.size() - 1).evaluation);
	}

	@Test
	void convergenceCurveNeverIncreases() {
		HarmonySearchScheduler hs = new HarmonySearchScheduler(topology, 3_000, 9);
		hs.schedule(tasks, capacities);
		List<ConvergenceLog.Point> points = hs.convergence().points();
		for (int i = 1; i < points.size(); i++) {
			assertTrue(points.get(i).bestFitness <= points.get(i - 1).bestFitness);
		}
	}

	@Test
	void budgetSmallerThanMemoryIsRejected() {
		assertThrows(IllegalArgumentException.class, () -> new HarmonySearchScheduler(topology, 29, 1));
	}
}
