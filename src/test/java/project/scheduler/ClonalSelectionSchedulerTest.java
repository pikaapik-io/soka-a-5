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

class ClonalSelectionSchedulerTest {
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
		ClonalSelectionScheduler a = new ClonalSelectionScheduler(topology, 2_000, 7);
		ClonalSelectionScheduler b = new ClonalSelectionScheduler(topology, 2_000, 7);
		assertArrayEquals(a.schedule(tasks, capacities), b.schedule(tasks, capacities));
		assertEquals(a.bestFitness(), b.bestFitness());
	}

	@Test
	void everyVmIndexIsValid() {
		int[] mapping = new ClonalSelectionScheduler(topology, 2_000, 3).schedule(tasks, capacities);
		assertEquals(tasks.length, mapping.length);
		for (int vm : mapping) {
			assertTrue(vm >= 0 && vm < capacities.length, "invalid VM index " + vm);
		}
	}

	@Test
	void resultIsNeverWorseThanKpbSeed() {
		ClonalSelectionScheduler csa = new ClonalSelectionScheduler(topology, 2_000, 11);
		int[] mapping = csa.schedule(tasks, capacities);

		int[] kpb = new KpbScheduler(100).schedule(tasks, capacities);
		FitnessFunction fitness = new FitnessFunction(
				new ScheduleEvaluator(topology.withTasks(tasks, capacities)), FitnessFunction.DEFAULT_W, kpb);
		double kpbFitness = fitness.evaluate(kpb);
		double csaFitness = fitness.evaluate(mapping);

		assertEquals(1.0, kpbFitness, 1e-12);
		assertEquals(csa.bestFitness(), csaFitness, 1e-12);
		assertTrue(csaFitness <= kpbFitness, "CSA " + csaFitness + " worse than KPB " + kpbFitness);
	}

	// 118 = 30 initial + 88 clones (one full cloning round), 121 adds the 3 receptor-editing antibodies.
	@ParameterizedTest
	@ValueSource(ints = {30, 31, 118, 120, 121, 1_234})
	void evaluationBudgetIsRespectedExactly(int budget) {
		ClonalSelectionScheduler csa = new ClonalSelectionScheduler(topology, budget, 5);
		csa.schedule(tasks, capacities);
		assertEquals(budget, csa.evaluations());

		List<ConvergenceLog.Point> points = csa.convergence().points();
		assertEquals(budget, points.get(points.size() - 1).evaluation);
	}

	@Test
	void convergenceCurveNeverIncreases() {
		ClonalSelectionScheduler csa = new ClonalSelectionScheduler(topology, 3_000, 9);
		csa.schedule(tasks, capacities);
		List<ConvergenceLog.Point> points = csa.convergence().points();
		for (int i = 1; i < points.size(); i++) {
			assertTrue(points.get(i).bestFitness <= points.get(i - 1).bestFitness);
		}
	}

	@Test
	void betterRanksMutateFewerTasks() {
		ClonalSelectionScheduler csa = new ClonalSelectionScheduler(topology, 1_000, 1);
		assertEquals(5, csa.mutatedTaskCount(1, 500));    // mutMin = 1%
		assertEquals(50, csa.mutatedTaskCount(10, 500));  // mutMax = 10%
		assertEquals(1, csa.mutatedTaskCount(1, 20));     // never fewer than one task
		for (int rank = 2; rank <= 10; rank++) {
			assertTrue(csa.mutatedTaskCount(rank, 500) >= csa.mutatedTaskCount(rank - 1, 500));
		}
	}

	@Test
	void invalidParametersAreRejected() {
		assertThrows(IllegalArgumentException.class, () -> new ClonalSelectionScheduler(topology, 29, 1));
		assertThrows(IllegalArgumentException.class, () -> new ClonalSelectionScheduler(
				topology, 0.5, 30, 10, 1.0, 0.01, 0.10, 30, 1_000, 1, 100));
		assertThrows(IllegalArgumentException.class, () -> new ClonalSelectionScheduler(
				topology, 0.5, 30, 31, 1.0, 0.01, 0.10, 3, 1_000, 1, 100));
	}
}
